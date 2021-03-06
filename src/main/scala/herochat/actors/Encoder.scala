package herochat.actors


import scala.util.{Try,Success,Failure}

import Numeric.Implicits._

import akka.actor.{ActorRef, Props, Actor, ActorLogging}

import za.co.monadic.scopus.{SampleFrequency, Voip}
import za.co.monadic.scopus.opus.{OpusEncoder, OpusDecoderShort}

import scodec._
import scodec.bits.{ByteVector, BitVector}
import scodec.codecs

import herochat.{AudioEncoding, AudioData}
import herochat.HcCodec._



object Encoder {
  def props(sampleLenMillis: Int, sampleRate: SampleFrequency, nChannels: Int): Props = Props(classOf[Encoder], sampleLenMillis, sampleRate, nChannels)
}

/* TODO: is it better to have one encoder per audio source? That would simplify the process if we
 * wanted to read audio from wav files with different PCM formats
 */
class Encoder(sampleLenMillis: Int, sampleRate: SampleFrequency, nChannels: Int) extends Actor with ActorLogging {
  import context._

  val enc = OpusEncoder(sampleRate, nChannels, Voip)
  enc.reset

  val subscribers = scala.collection.mutable.Set[ActorRef]()

  def OpusEncodeAndSend(buf: Array[Short], endOfSegment: Boolean): Unit = {
    if (endOfSegment) log.debug(s"encoding endOfSegment")
    enc(buf) match {
      case Success(enc_frame: Array[Byte]) =>
        subscribers.foreach(sub => sub ! HcAudioMessage(AudioData(AudioEncoding.Opus, endOfSegment, ByteVector(enc_frame))))
      case Failure(f) =>
        log.debug(s"Opus encoding error: $f, $endOfSegment, ${buf.length}, $nShorts")
        /* It is important to send EndOfSegment messages. If opus encoding fails, send an empty frame */
        if (endOfSegment) {
          subscribers.foreach(sub => sub ! HcAudioMessage(AudioData(AudioEncoding.Opus, endOfSegment, ByteVector.empty)))
        }
    }
  }
  /* Accepted buf sizes: 120, 240, 480, or 960 */
  /* Opus Encoder encodes nShorts at a time */
  val nShorts = sampleRate() * sampleLenMillis / 1000 * nChannels

  def active: Receive = {
    case AddSubscriber(sub) =>
      subscribers += sub
    case RemoveSubscriber(sub) =>
      subscribers -= sub
    case AudioData(AudioEncoding.Pcm, endOfSegment, bytes) =>
      //only encode if someone is listening (tree)
      if (!subscribers.isEmpty) {
        /* Separate incoming audio into chunks for Opus encoding */
        codecs.vector(codecs.short16L).decode(bytes.bits) match {
          case Attempt.Successful(DecodeResult(chunks, rem)) =>
            /* encode each chunk */
            val fShorts = chunks.grouped(nShorts).toArray
            fShorts.zipWithIndex.foreach { case(frame, i) =>
              OpusEncodeAndSend(frame.toArray, endOfSegment && (i == fShorts.length - 1))
            }
          case x => log.debug(s"Error chunking Audio: $x, $bytes, $endOfSegment")
        }
      }
    case _ @ msg => log.debug(s"Active: Bad Msg: $msg")
  }

  def receive = active
}
