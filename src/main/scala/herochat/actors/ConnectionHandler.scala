package herochat.actors



import scala.util.{Try,Success,Failure}

import akka.actor.{ActorRef, Props, Actor, ActorLogging}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import java.net.{InetAddress, InetSocketAddress}
import java.io.{FileOutputStream}

import za.co.monadic.scopus.{Sf8000, Sf48000, Voip}
import za.co.monadic.scopus.opus.{OpusDecoderShort}

import scodec._
import scodec.bits._
import scodec.codecs._

import herochat.HcCodec._


object ConnectionHandler {
  def props(address: InetSocketAddress): Props = Props(classOf[ConnectionHandler], address)
}

/*
 * Handles incoming connections
 */
class ConnectionHandler(address: InetSocketAddress) extends Actor with ActorLogging {
  import context._
  import Tcp._
  
  IO(Tcp) ! Tcp.Bind(self, address: InetSocketAddress)

  def receive: Receive = {
    case Connected(remoteAddress, localAddress) =>
      log.debug(s"received connection from: $remoteAddress -> $localAddress")
      parent ! BigBoss.IncomingConnection(remoteAddress, localAddress, sender)
    case _ @ msg => log.debug(s"Bad Msg: $msg")
  }
}
