package herochat.actors

import scala.math

import akka.actor.{ActorRef, Props, Actor, ActorLogging}

import scodec.bits.ByteVector

import javax.sound.sampled.{DataLine, SourceDataLine, AudioSystem, AudioFormat, Mixer}
import javax.sound.sampled.{Control, BooleanControl, EnumControl, FloatControl}

import herochat.{AudioEncoding, AudioData, AudioUtils}


object PlayQueue {
  def props(sourceLine: SourceDataLine): Props = Props(classOf[PlayQueue], sourceLine)
}

/* line.write is a blocking call, this actor acts as a queue for audio data if we are sent more data
 * than we can play without blocking
 * TODO: drop audio if we receive an excessive, DDOS amount
 */
class PlayQueue(sourceLine: SourceDataLine) extends Actor with ActorLogging {
  import context._

  def receive: Receive = {
    case AudioData(AudioEncoding.Pcm, endOfSegment, bytes) =>
      if (!sourceLine.isRunning) {
        sourceLine.start()
      }

      sourceLine.write(bytes.toArray, 0, bytes.length.toInt)

      if (endOfSegment) {
        val before = sourceLine.available
        sourceLine.drain()
        val after = sourceLine.available
        log.debug(s"playing endOfSegment: $before, $after")
        sourceLine.stop()
        parent ! PeerActor.StoppedSpeaking
      }
    case msg => log.debug(s"Bad msg $msg")
  }
}


object AudioPlayer {
  def props(lineInfo: DataLine.Info, mixerInfo: Mixer.Info): Props = Props(classOf[AudioPlayer], lineInfo, mixerInfo)
}

/**
 *  TODO: debug method to show if lines are running
 */
class AudioPlayer(lineInfo: DataLine.Info, mixerInfo: Mixer.Info) extends Actor with ActorLogging {
  import context._
  import AudioPlayer._
  import herochat.AudioControl._

  override def postStop {
    deactivateAudioLine()
  }

  log.debug(s"AudioPlayer starting on Mixer: $mixerInfo")
  //Initialize javax sound system
  val inmix = AudioUtils.sbtCompatibilityBlock {
    AudioSystem.getMixer(mixerInfo)
  }
  /* Should I move sourceLine to playQueue? */
  val sourceLine = inmix.getLine(lineInfo).asInstanceOf[SourceDataLine]
  val playQueue = context.actorOf(PlayQueue.props(sourceLine), "playQueue")

  log.debug(s"this line: $sourceLine, ${inmix.getMaxLines(lineInfo)}")
  log.debug(s"inmix lines: ${inmix.getSourceLines.length}  ${inmix.getSourceLines.mkString(" :: ")}")

  def activateAudioLine() = {
    sourceLine.open()
  }
  activateAudioLine()

  val mute = AudioUtils.getLineControl[BooleanControl](sourceLine, BooleanControl.Type.MUTE)
  val gain = AudioUtils.getLineControl[FloatControl](sourceLine, FloatControl.Type.MASTER_GAIN)
  val nothere = AudioUtils.getLineControl[FloatControl](sourceLine, FloatControl.Type.AUX_SEND)
  //log.debug(s"volume stuff1: $mute, $gain, $nothere")
  //log.debug(s"volume stuff2: ${mute.map(_.getValue)}, ${gain.map(_.getValue)}, ${gain.map(_.getMinimum)}, ${gain.map(_.getMaximum)}, ${gain.map(_.getPrecision)}")
  //Some(-80.0), Some(6.0206), Some(0.625)

  def deactivateAudioLine() = {
    sourceLine.close()
  }

  /* TODO: Doesn't belong here */
  def percent_to_decibel(percent_volume: Double): Double = {
    10.0 * scala.math.log10(percent_volume)
  }

  def active(): Receive = {
    /* TODO: format the response messages; should these respond with setPeerState messages? */
    case Mute =>
      mute.foreach(_.setValue(true))
      log.debug(s"player $mute")
    case Unmute =>
      mute.foreach(_.setValue(false))
      log.debug(s"player unmute $mute")
    case GetVolume => sender ! gain.map(_.getValue)
    case GetVolumePrecision => sender ! gain.map(_.getPrecision)
    case GetVolumeBounds => sender ! gain.map(x => (x.getMinimum, x.getMaximum))
    case SetVolume(vol: Double) =>
      val decibelVol = percent_to_decibel(vol).toFloat
      log.debug(s"changing volume from ${gain.map(_.getValue)} to $decibelVol")
      /* What happens if we go over the max allowed value?? We should Clamp */
      gain.foreach(_.setValue(decibelVol))
      log.debug(s"player volume ${gain.map(_.getValue)}")

    case msg: AudioData => playQueue ! msg
    case PeerActor.StoppedSpeaking => parent ! PeerActor.StoppedSpeaking
    case _ @ msg => log.debug(s"Bad Msg: $msg")
  }

  def receive = active
}
