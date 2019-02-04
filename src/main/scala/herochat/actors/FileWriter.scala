package herochat.actors


import scala.util.{Try,Success,Failure}

import akka.actor.{ActorRef, Props, Actor, ActorLogging}

import java.nio.file.Paths
import java.nio.channels.FileChannel
import java.io.{FileOutputStream}

import scodec.Codec
import scodec.codecs.uint32L
import scodec.bits._

import herochat.{AudioEncoding, AudioData, WavCodec}

object FileWriter {
  def props(): Props = Props(classOf[FileWriter])
  case class Open(filename: String)
  case class OpenWav(filename: String, format: WavCodec.PcmFormat)
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
    case FileWriter.OpenWav(filename, format) =>
      log.debug(s"Opening wave at path: $filename")
      become(writeWavFile(new FileOutputStream(filename), format))
    case msg: AudioData => ()
    case _ @ msg => log.debug(s"$self-inactive: Bad Msg: $msg")
  }

  def writeWavFile(file: FileOutputStream, format: WavCodec.PcmFormat): Receive = {
    val header = WavCodec.wavHeader(format).toArray
    val channel = file.getChannel
    var dataLength: Long = 0
    file.write(header)
    file.write(hex"64617461".toArray)

    val x: Receive = {
      case AudioData(encoding, endOfSegment, bytes) =>
        dataLength += bytes.length
        file.write(bytes.toArray)

        if (endOfSegment) {
          log.debug(s"End Of Audio Segment: Closing file at path: $file")
          //Rewrite header lengths
          /* TODO: size is off by one in case of odds */
          log.debug(s"Writing total length: ${dataLength+36}, ${dataLength}")
          channel.position(4)
          channel.write(uint32L.encode(dataLength+40).require.toByteBuffer)
          channel.position(40)
          channel.write(uint32L.encode(dataLength).require.toByteBuffer)
          log.debug(s"Closing file at path: $file")
          file.close()
          become(inactive)
        }
      case FileWriter.Close =>
        //Rewrite header lengths
        /* TODO: size is off by one in case of odds */
        log.debug(s"Writing total length: ${dataLength+36}, ${dataLength}")
        channel.position(4)
        channel.write(uint32L.encode(dataLength+36).require.toByteBuffer)
        channel.position(40)
        channel.write(uint32L.encode(dataLength).require.toByteBuffer)
        log.debug(s"Closing file at path: $file")
        file.close()
        become(inactive)
      case _ @ msg => log.debug(s"$self-active: Bad Msg: $msg")
    }
    x
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
