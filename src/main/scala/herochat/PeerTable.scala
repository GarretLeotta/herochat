package herochat


import scala.util.{Try,Success,Failure}
import scala.collection.mutable.Map

import akka.actor.{ActorRef}

import java.net.{InetSocketAddress}
import java.time.{Instant}
import java.util.UUID

object PeerTable {
  /* RemoteAddress, ActorRef, IsLocalInit, TimeConnected
   * PK: ActorRef
   */
  type PreShakePeer = Tuple4[InetSocketAddress, ActorRef, Boolean, Instant]
  /* RemoteAddress, ActorRef, IsLocalInit, TimeConnected, remoteUUID, RemotePexAddress
   * Unique identifiers: ActorRef, remoteUUID
   */
  type PostShakePeer = Tuple6[InetSocketAddress, ActorRef, Boolean, Instant, UUID, InetSocketAddress]
}

/**
 * TODO: peers should be an injective function, or one-to-one, ActorRef is a set
 * TODO: how to use akka logging from outside of actor class?
 */
class PeerTable(bigboss: ActorRef) {
  import PeerTable._
  val shakingPeers = scala.collection.mutable.Set[PreShakePeer]()
  val shookPeers = scala.collection.mutable.Set[PostShakePeer]()


  /* Returns a vector of Peers, both pre and post-shake, that have RemoteAddress == remoteAddr
   */
  def getIsShookByAddr(remoteAddr: InetSocketAddress): Vector[Either[PreShakePeer, PostShakePeer]] = {
    shakingPeers.filter(tup => tup._1 == remoteAddr).toVector.map(x=>Left(x)) ++
      shookPeers.filter(tup => tup._1 == remoteAddr).toVector.map(x=>Right(x))
  }
  /* Returns a vector of Peers, both pre and post-shake, that have peerRef == peerRef
   */
  def getIsShookByARef(peerRef: ActorRef): Option[Either[PreShakePeer, PostShakePeer]] = {
    val shakeFilt = shakingPeers.filter(tup => tup._2 == peerRef).toVector.map(x=>Left(x)).lift(0)
    val shookFilt = shookPeers.filter(tup => tup._2 == peerRef).toVector.map(x=>Right(x)).lift(0)
    shakeFilt orElse shookFilt
  }

  /* TODO: This should only return one peer
   * TODO: use isShook?
   */
  def getUnShookByPeerRef(peerRef: ActorRef): Option[PreShakePeer] = {
    shakingPeers.filter(tup => tup._2 == peerRef).toVector.lift(0)
  }
  def getShookByAddr(remoteAddr: InetSocketAddress): Option[PostShakePeer] = {
    shookPeers.filter(tup => tup._1 == remoteAddr).toVector.lift(0)
  }
  def getShookByPexAddr(remotePexAddr: InetSocketAddress): Option[PostShakePeer] = {
    shookPeers.filter(tup => tup._6 == remotePexAddr).toVector.lift(0)
  }
  def getShookByUUID(remoteUUID: UUID): Option[PostShakePeer] = {
    shookPeers.filter(tup => tup._5 == remoteUUID).toVector.lift(0)
  }

  /* requested remote address should not be in any peer lists (pex or actual) and should not equal
   * this host's listen address
   * returns true if verified
   * instead of boolean, return an Option or Either, so we can return why its wrong??
   */
  def preShakeVerify(remoteAddr: InetSocketAddress, listenAddr: InetSocketAddress): Boolean = {
    val in_lists = //shaking_peers.map(x => x._1).contains(remoteAddr) ||
      shookPeers.map(x => x._1).contains(remoteAddr) ||
      shookPeers.map(x => x._6).contains(remoteAddr)
    val is_self = remoteAddr == listenAddr
    //TODO: implement logging outside of actors
    //println(s"$bigboss: preShakeVerify, $in_lists, $is_self, $remoteAddr, $listenAddr, ${shookPeers.map(x => x._1)}, ${shookPeers.map(x => x._6)}")
    return !(in_lists || is_self)
  }

  /*
   * we repeat checks between this and preshake, but I think we need to, becuz race condition where
   * another peer transitions from shaking to shook
   */
  def postShakeVerify(remoteAddr: InetSocketAddress, remotePexAddr: InetSocketAddress): Boolean = {
    val in_lists = shookPeers.map(x => x._1).contains(remoteAddr) ||
      shookPeers.map(x => x._6).contains(remoteAddr) ||
      shookPeers.map(x => x._1).contains(remotePexAddr) ||
      shookPeers.map(x => x._6).contains(remotePexAddr)
    //println(s"$bigboss: postShakeVerify, $in_lists, $remoteAddr, $remotePexAddr, ${shookPeers.map(x => x._1)}, ${shookPeers.map(x => x._6)}")
    return !in_lists
  }

  /*
   */
  def completeShake(remoteAddr: InetSocketAddress, peerRef: ActorRef, uuid: UUID, pexAddr: InetSocketAddress): Unit = {
    val (a,b,c,d) = shakingPeers.filter(tup => tup._1 == remoteAddr && tup._2 == peerRef).toVector(0)
    shakingPeers -= ((a,b,c,d))
    shookPeers += ((a, b, c, d, uuid, pexAddr))
  }

  def updatePexAddress(uuid: UUID, newPexAddr: InetSocketAddress): Unit = {
    println("todo")
  }
}
