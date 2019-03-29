package herochat.actors


import scala.util.{Try,Success,Failure}

import akka.actor.{ActorRef, Props, Actor, ActorLogging}

import java.io.{FileInputStream}

import scodec.bits._
import scodec.{Codec, Attempt, DecodeResult}

import herochat.{AudioEncoding, AudioData, WavCodec}

object FileReader {
  def props(): Props = Props(classOf[FileReader])

  case class Open(filename: String)
  case class OpenWav(filename: String, ourFormat: WavCodec.PcmFormat)
  case object Close
  case object Next
}


/* TODO: support different file encodings */
class FileReader() extends Actor with ActorLogging {
  import context._

  val bufSz = 24000
  val bytes = new Array[Byte](bufSz)

  val subscribers = scala.collection.mutable.Set[ActorRef]()

  def inactive: Receive = {
    case AddSubscriber(sub) =>
      subscribers += sub
    case RemoveSubscriber(sub) =>
      subscribers -= sub
    case FileReader.Open(filename) =>
      log.debug(s"Opening file at path: $filename")
      become(readFromFile(new FileInputStream(filename), AudioEncoding.Pcm))
      self ! FileReader.Next
    case FileReader.OpenWav(filename, ourFormat) =>
      log.debug(s"Opening WAV file at path: $filename")
      become(readWavFile(new FileInputStream(filename), ourFormat))
    case _ @ msg => log.debug(s"$self-inactive: Bad Msg: $msg")
  }

  def readWavFile(file: FileInputStream, ourFormat: WavCodec.PcmFormat): Receive = {
    //read header
    val read = file.read(bytes, 0, 44)
    if (read == 44) {
      WavCodec.fmtChunkCodec.decode(ByteVector(bytes.slice(8, 36)).bits) match {
        case Attempt.Successful(DecodeResult(pcmFormat, rem)) =>
          if (ourFormat.toJavax.matches(pcmFormat.toJavax)) {
            log.debug(s"Compatible WAVE Format")
            self ! FileReader.Next
            readFromFile(file, AudioEncoding.Pcm)
          } else {
            log.debug(s"Incompatible WAVE Format")
            file.close()
            inactive
          }
        case x =>
          log.debug(s"Header Decoding Error: $x")
          file.close()
          inactive
      }
    } else {
      log.debug(s"Invalid WAV file: Stub")
      file.close()
      inactive
    }
  }


  /* TODO: rate limit this stuff, so it sends it out at approx the time it takes irl */
  def readFromFile(file: FileInputStream, encoding: AudioEncoding.AudioEncoding): Receive = {
    case FileReader.Next =>
      file.read(bytes) match {
        case -1 =>
          self ! FileReader.Close
        case bytesRead =>
          val lastSegment = file.available == 0
          val segment = ByteVector(bytes.slice(0, bytesRead))
          if (lastSegment) {
            log.debug(s"last segment hit, $bytesRead, ${segment.length}")
          }
          subscribers.foreach(sub => sub ! AudioData(encoding, lastSegment, segment))
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
