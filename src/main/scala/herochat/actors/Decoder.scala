package herochat.actors



import scala.util.{Try,Success,Failure}

import akka.actor.{ActorRef, Props, Actor, ActorLogging}

import za.co.monadic.scopus.{SampleFrequency}
import za.co.monadic.scopus.opus.{OpusDecoderShort}

import scodec._
import scodec.bits.ByteVector
import scodec.codecs._

import herochat.{AudioEncoding, AudioData}
import herochat.HcCodec._


object Decoder {
  def props(sampleRate: SampleFrequency, nChannels: Int): Props = Props(classOf[Decoder], sampleRate, nChannels)
}

class Decoder(sampleRate: SampleFrequency, nChannels: Int) extends Actor with ActorLogging {
  import context._

  var subscribers = scala.collection.mutable.Set[ActorRef]()

  val dec = OpusDecoderShort(sampleRate, nChannels)
  dec.reset

  def active: Receive = {
    case AddSubscriber(sub) =>
      subscribers += sub
    case RemoveSubscriber(sub) =>
      subscribers -= sub
    case AudioData(AudioEncoding.Opus, endOfSegment, bytes) =>
      if (endOfSegment) log.debug(s"got endOfSegment")

      dec(bytes.toArray) match {
        case Success(dec_frame) =>
          codecs.vector(codecs.short16L).encode(dec_frame.toVector) match {
            case Attempt.Successful(opusAudio) =>
              val msg = AudioData(AudioEncoding.Pcm, endOfSegment, opusAudio.bytes)
              subscribers.foreach(_ ! msg)
            case x => log.debug(s"Failed to encode Audio to HcMessage: $x")
          }
        case Failure(f) => log.debug("Decoder-active: decoder error:", f)
      }
    case _ @ msg => log.debug(s"$self-active: Bad Msg: $msg")
  }
  def receive = active
}
