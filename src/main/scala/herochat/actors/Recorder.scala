package herochat.actors

import scala.collection.JavaConverters._

import akka.actor.{ActorRef, Props, Actor, ActorLogging}

import java.nio.file.Paths
import java.io.FileOutputStream
import javax.sound.sampled.{DataLine, TargetDataLine, AudioSystem, AudioFormat, Mixer}

import scodec.bits.ByteVector

import herochat.{AudioEncoding, AudioData}

object Recorder {
  def props(format: AudioFormat, bufSize: Int, mixerInfo: Mixer.Info): Props = Props(classOf[Recorder], format, bufSize, mixerInfo)

  case object Record
  case object Pause
  case object Next
}


/**
 * input args: audio encoding, sample rate, sampleSizeInBits, channels, frame size, frame rate, big endian,
 * bufferSize, audio device name, output_actor
 */
class Recorder(format: AudioFormat, bufSize: Int, mixerInfo: Mixer.Info) extends Actor with ActorLogging {
  import context._

  //sbt compatibility
  val cl = classOf[javax.sound.sampled.AudioSystem].getClassLoader
  val old_cl: java.lang.ClassLoader = Thread.currentThread.getContextClassLoader
  Thread.currentThread.setContextClassLoader(cl)
  override def postStop {
    log.debug(s"stopping javax line")
    targetLine.stop()
    targetLine.close()
    log.debug(s"Stopping $self, resetting thread context class loader")
    Thread.currentThread.setContextClassLoader(old_cl)
  }

  //set up javax Sound Stuff
  val targetInfo = new DataLine.Info(classOf[TargetDataLine], format, bufSize)
  val inMixer = AudioSystem.getMixer(mixerInfo)
  val targetLine = inMixer.getLine(targetInfo).asInstanceOf[TargetDataLine]
  //Open and start the target line
  log.debug(s"starting javax line")
  targetLine.open()
  targetLine.start()
  log.debug(s"javax line initiated")

  def handle_out(actor: ActorRef) = {}

  var subscribers = scala.collection.mutable.Set[ActorRef]()
  var buf: Array[Byte] = Array.ofDim[Byte](bufSize)

  /**
   * two states: active and inactive
   * active: recording audio and sending to subscribers
   * inactive: line is open, but not reading data
   *   question: when we go from inactive to active, is the data stale?
   * javax sound resources are allocated on Recorder actor creation and deallocated in postStop
   */
  def baseHandler: Receive = {
    case AddSubscriber(sub) =>
      subscribers += sub
    case RemoveSubscriber(sub) =>
      subscribers -= sub
  }

  /* This is a blocking call, meaning that any pause commands will have a delay of at most bufSize[Seconds] */
  def recordAndSend(lastSegment: Boolean): Unit = {
    val bytesRead = targetLine.read(buf, 0, bufSize)
    subscribers.foreach( sub =>
      sub ! AudioData(AudioEncoding.Pcm, lastSegment, ByteVector(buf.slice(0, bytesRead+1)))
    )
  }

  def inactive: Receive = baseHandler orElse {
    case Recorder.Record =>
      log.debug(s"RECORD")
      become(active)
      self ! Recorder.Next
    case Recorder.Next =>
      log.debug(s"recording endOfSegment")
      recordAndSend(true)
    case _ @ msg => log.debug(s"Inactive: Bad Msg: $msg")
  }

  /* NOTE: Recording goes for an extra bufSize[InSeconds] after hitting mute
   * Right now, not too big of a deal. */
  def active: Receive = baseHandler orElse {
    case Recorder.Pause =>
      log.debug(s"PAUSE")
      become(inactive)
    case Recorder.Next =>
      recordAndSend(false)
      self ! Recorder.Next
    case _ @ msg => log.debug(s"Active: Bad Msg: $msg")
  }

  def receive = inactive
}
