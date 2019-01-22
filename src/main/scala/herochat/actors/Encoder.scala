package herochat.actors


import scala.util.{Try,Success,Failure}

import Numeric.Implicits._

import akka.actor.{ActorRef, Props, Actor, ActorLogging}

import za.co.monadic.scopus.{SampleFrequency, Sf8000, Sf48000, Voip}
import za.co.monadic.scopus.opus.{OpusEncoder, OpusDecoderShort}
import za.co.monadic.scopus.pcm.{PcmEncoder, PcmDecoderShort}


import java.nio.file.Paths
import java.nio.channels.FileChannel
import java.io.{FileOutputStream, FileInputStream, DataOutputStream, BufferedOutputStream, DataInputStream, BufferedInputStream}

import cats.effect.IO

import scodec.bits.ByteVector
import scodec.bits.BitVector
import scodec.Codec
import scodec.codecs
import scodec.stream.decode
import scodec.stream.decode.{many}

import herochat.{AudioEncoding, AudioData}
import herochat.HcCodec._



object Encoder {
  def props(sampleLenMillis: Int, sampleRate: SampleFrequency, nChannels: Int): Props = Props(classOf[Encoder], sampleLenMillis, sampleRate, nChannels)
}


class Encoder(sampleLenMillis: Int, sampleRate: SampleFrequency, nChannels: Int) extends Actor with ActorLogging {
  import context._

  val enc = OpusEncoder(sampleRate, nChannels, Voip)
  enc.reset

  var subscribers = scala.collection.mutable.Set[ActorRef]()

  //buf should be size 160
  def OpusEncodeAndSend(buf: Array[Short], endOfSegment: Boolean): Unit = {
    enc(buf) match {
      case Success(enc_frame: Array[Byte]) =>
        val payload = Codec.encode(AudioData(AudioEncoding.Opus, endOfSegment, ByteVector(enc_frame))).require.bytes
        val hcMsg = HcMessage(MsgTypeAudio, payload.length.toInt, payload)
        subscribers.foreach(sub => sub ! hcMsg)
      case Failure(f) => log.debug("Opus encoding error:", f)
    }
  }
  val nShorts = sampleRate() * sampleLenMillis / 1000 * nChannels

  var debug_print_limit = 0

  def active: Receive = {
    case AddSubscriber(sub) =>
      subscribers += sub
    case RemoveSubscriber(sub) =>
      subscribers -= sub
    case AudioData(AudioEncoding.Pcm, endOfSegment, bytes) =>
      //only encode if someone is listening (tree)
      if (!subscribers.isEmpty) {
        val fShorts: Array[Vector[Short]] = codecs.vector(codecs.short16L).decode(bytes.bits).require.value.grouped(nShorts).toArray
        //encode each frame
        fShorts.zipWithIndex.foreach { case(frame, i) =>
          OpusEncodeAndSend(frame.toArray, endOfSegment && (i == fShorts.length - 1))
        }
      }
    case _ @ msg => log.debug(s"Active: Bad Msg: $msg")
  }

  def receive = active
}
