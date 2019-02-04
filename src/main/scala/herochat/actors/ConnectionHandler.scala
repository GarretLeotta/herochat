package herochat.actors



import scala.util.{Try,Success,Failure}

import akka.actor.{ActorRef, Props, Actor, ActorLogging}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import java.net.InetSocketAddress
import java.io.{FileOutputStream}

import za.co.monadic.scopus.{Sf8000, Sf48000, Voip}
import za.co.monadic.scopus.opus.{OpusDecoderShort}

import scodec._
import scodec.bits._
import scodec.codecs._

import herochat.HcCodec._


object ConnectionHandler {
  def props(port: Int): Props = Props(classOf[ConnectionHandler], port)
}

/*
 * Handles incoming connections
 */
class ConnectionHandler(port: Int) extends Actor with ActorLogging {
  import context._
  import Tcp._
  /* TODO: ipv4? - For now, no ipv4 support, much easier to just deal with ipv6.
   * No NAT punching required.
   */
  IO(Tcp) ! Tcp.Bind(self, new InetSocketAddress("::1", port))

  def receive: Receive = {
    case Connected(remoteAddress, localAddress) =>
      log.debug(s"received connection from: $remoteAddress, $localAddress")
      parent ! BigBoss.IncomingConnection(remoteAddress, localAddress, sender)
    case _ @ msg => log.debug(s"Bad Msg: $msg")
  }
}
