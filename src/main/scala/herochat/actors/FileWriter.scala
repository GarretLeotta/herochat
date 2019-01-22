package herochat.actors


import scala.util.{Try,Success,Failure}

import akka.actor.{ActorRef, Props, Actor, ActorLogging}

import java.nio.file.Paths
import java.nio.channels.FileChannel
import java.io.{FileOutputStream}

import scodec.bits.ByteVector

import herochat.{AudioEncoding, AudioData}

object FileWriter {
  def props(): Props = Props(classOf[FileWriter])
  case class Open(filename: String)
  case object Close
  case object Start
  case object Pause
}


/* Only logs one segment, should we enable it to log more?? */
class FileWriter() extends Actor with ActorLogging {
  import context._

  def inactive: Receive = {
    case FileWriter.Open(filename) =>
      log.debug(s"Opening file at path: $filename")
      become(writeToFile(new FileOutputStream(filename)))
    case msg: AudioData => ()
    case _ @ msg => log.debug(s"$self-inactive: Bad Msg: $msg")
  }

  def writeToFile(file: FileOutputStream): Receive = {
    case AudioData(encoding, endOfSegment, bytes) =>
      file.write(bytes.toArray)
      if (endOfSegment) {
        log.debug(s"End Of Audio Segment: Closing file at path: $file")
        file.close()
        become(inactive)
      }
    case FileWriter.Close =>
      log.debug(s"Closing file at path: $file")
      file.close()
      become(inactive)
    case _ @ msg => log.debug(s"$self-active: Bad Msg: $msg")
  }

  def receive = inactive
}
