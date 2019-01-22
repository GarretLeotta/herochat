package herochat.actors


import scala.util.{Try,Success,Failure}

import akka.actor.{ActorRef, Props, Actor}

import java.nio.file.Paths
import java.nio.channels.FileChannel
import java.io.{FileOutputStream}

import scodec.bits.ByteVector


object SockWriter {
  def props(): Props = Props(classOf[SockWriter])
  case class Open(filename: String)
  case class Close()
  case class Start()
  case class Pause()
}


class SockWriter() extends Actor {
  import context._
  
  def inactive: Receive = {
    case SockWriter.Open(filename) =>
      println(s"Opening file at path: $filename")
      become(write_to_file(new FileOutputStream(filename)))
    case _ @ msg => println(s"SockWriter-inactive: Bad Msg: $msg")
  }
  
  def write_to_file(file: FileOutputStream): Receive = {
    case bytes: ByteVector =>
      file.write(bytes.toArray)
    case SockWriter.Close =>
      println(s"Closing file at path: $file")
      file.close()
      become(inactive)
    case _ @ msg => println(s"FileWriter-active: Bad Msg: $msg")
  }
  
  def receive = inactive
}
