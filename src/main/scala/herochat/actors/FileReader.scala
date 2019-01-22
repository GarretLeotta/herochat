package herochat.actors


import scala.util.{Try,Success,Failure}

import akka.actor.{ActorRef, Props, Actor, ActorLogging}

import java.io.{FileInputStream}

import scodec.bits.ByteVector

import herochat.{AudioEncoding, AudioData}

object FileReader {
  def props(): Props = Props(classOf[FileReader])

  case class Open(filename: String)
  case object Close
  case object Next
}


/* TODO: support different file encodings */
class FileReader() extends Actor with ActorLogging {
  import context._

  val bufSz = 24000
  val bytes = new Array[Byte](bufSz)

  var subscribers = scala.collection.mutable.Set[ActorRef]()

  def inactive: Receive = {
    case AddSubscriber(sub) =>
      subscribers += sub
    case RemoveSubscriber(sub) =>
      subscribers -= sub
    case FileReader.Open(filename) =>
      log.debug(s"$self - Opening file at path: $filename")
      become(readFromFile(new FileInputStream(filename)))
      self ! FileReader.Next
    case _ @ msg => log.debug(s"$self-inactive: Bad Msg: $msg")
  }

  /* TODO: rate limit this stuff, so it sends it out at approx the time it takes irl */
  def readFromFile(file: FileInputStream): Receive = {
    case FileReader.Next =>
      file.read(bytes) match {
        case -1 =>
          self ! FileReader.Close
        case bytesRead =>
          val lastSegment = file.available == 0
          subscribers.foreach(sub => sub ! AudioData(AudioEncoding.Pcm, lastSegment, ByteVector(bytes.slice(0,bytesRead+1))))
          self ! FileReader.Next
      }
    case FileReader.Close =>
      log.debug(s"$self - Closing file at path: $file")
      file.close()
      become(inactive)
    case _ @ msg => log.debug(s"$self-active: Bad Msg: $msg")
  }

  def receive = inactive
}
