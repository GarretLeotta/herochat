package herochat.actors


import akka.actor.{Actor}

import scodec.bits.ByteVector


trait OutputterCompanion {
  case class Open(outDesc: OutputDescriptor)
  case class Close()
}


trait OutputDescriptor {
  val descriptorName : String
}


trait Outputter extends Actor {
  import context._
  
  def outputBytes(bytes : ByteVector)
  def openOutput(outDesc : OutputDescriptor)
  def closeOutput
  
  //Don't know how to do this in a satisfying way yet
  /*
  def inactive: Receive = {
    case Outputter.Open(outDesc) =>
      openOutput(outDesc)
      become(active)
    case _ @ msg => println(s"Outputter-inactive: Bad Msg: $msg")
  }
  
  def active: Receive = {
    case bytes: ByteVector =>
      outputBytes(bytes)
     case Outputter.Close =>
      closeOutput
      become(inactive)
    case _ @ msg => println(s"Outputter-active: Bad Msg: $msg")
  }
  
  def receive = inactive
  */
  def receive = {
    case _ @ msg => println(s"Outputter-active: Bad Msg: $msg")
  }
}