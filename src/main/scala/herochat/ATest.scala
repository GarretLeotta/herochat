package herochat

import language.postfixOps

import scala.concurrent.duration._

import scala.util.{Try,Success,Failure}

import akka.actor.{ActorSystem, Props, PoisonPill, Actor, ActorRef, ActorLogging}

import javax.sound.sampled._

import java.io.{File, FileOutputStream, FileInputStream}

import cats.effect.IO
import cats.implicits._

import java.net.{InetAddress, InetSocketAddress}

import org.scalacheck._
import Prop._
import scodec.stream._
import scodec.stream.decode.{many, emits}
import scodec.codecs._
import scodec._
import scodec.bits._

import fs2.Chunk

import HcCodec._
import AuthPayload.{AuthTypeUUID, AuthTypeLobbyName, AuthTypeNickname, AuthTypePort}
import herochat.actors.BigBoss

case class ShutDown()

class Killswitch extends Actor with ActorLogging {
  import context._
  override def receive: Receive = {
    case ShutDown =>
      log.debug(s"got the shutdown message from $sender")
      context.system.terminate()
  }
}

/*
 * TODO: Stereo doesn't work, pops and crackles, think it has something to do with the scopus implementation
 * see C:\Users\garre\Downloads\scopus-master\src\main\scala\za\co\monadic\scopus\opus\OpusEncoder.scala: 56
 * passes length of audio, regardless of number of channels. not according to opus spec
 *
 * ISSUES: There are a lot of very similar types being used in this program, bitvector, bytevector, vector[byte], array[byte]
 * Need to figure out which types I'll use when transferring data between actors and peers, need to be consistent
 *
*/
object ATest  {
  def main(args: Array[String]): Unit = {
    val xMsg = AudioData(AudioEncoding.Pcm, false, hex"deadbeef")

    val encodedMsg = Codec[AudioData].encode(xMsg).require

    println(s"$encodedMsg")



  }
}
