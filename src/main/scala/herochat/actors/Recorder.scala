package herochat.actors

import scala.collection.JavaConverters._
import scala.math.min

import akka.actor.{ActorRef, Props, Actor, ActorLogging}

import java.nio.file.Paths
import java.io.FileOutputStream
import javax.sound.sampled.{DataLine, TargetDataLine, AudioSystem, AudioFormat, Mixer}

import scodec.bits.ByteVector

import herochat.{AudioEncoding, AudioData}

object Recorder {
  def props(lineInfo: DataLine.Info, mixerInfo: Mixer.Info): Props = Props(classOf[Recorder], lineInfo, mixerInfo)

  case object Record
  case object Pause
  case object Next
}


/**
 * input args: audio encoding, sample rate, sampleSizeInBits, channels, frame size, frame rate, big endian,
 * bufferSize, audio device name, output_actor
 */
class Recorder(lineInfo: DataLine.Info, mixerInfo: Mixer.Info) extends Actor with ActorLogging {
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

  log.debug(s"Recorder starting on Mixer: $mixerInfo")
  //set up javax Sound Stuff
  val inMixer = AudioSystem.getMixer(mixerInfo)
  val targetLine = inMixer.getLine(lineInfo).asInstanceOf[TargetDataLine]
  //Open and start the target line
  log.debug(s"starting javax line")
  targetLine.open()
  targetLine.start()
  log.debug(s"javax line initiated")

  def handle_out(actor: ActorRef) = {}

  var subscribers = scala.collection.mutable.Set[ActorRef]()
  var buf: Array[Byte] = Array.ofDim[Byte](lineInfo.getMaxBufferSize)

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

  /* targetLine.read is a blocking call, meaning that any pause commands will have a delay of at most bufSize[Seconds]
   * This caused a bug, spam-clicking PTT would build up a large delay before all the commands were read.
   */
  def recordAndSend(lastSegment: Boolean): Unit = {
    /* NOTE: large buffer sizes can cause "cutoff" - Once I let go of PTT, up to the last bufSzInSeconds
     * of audio are dropped. - cutoff is mitigated by PTT release delay
     * NOTE: 2: Pretty sure this method will be called a ton of times, (targetLine.available == buf.length),
     * will be false over and over again. Might be useful to only send Next every (bufSzInSeconds / 2)
     */
    if (lastSegment || targetLine.available >= buf.length) {
      val bytesToRead = min(targetLine.available, buf.length)
      val bytesRead = targetLine.read(buf, 0, bytesToRead)
      subscribers.foreach( sub =>
        sub ! AudioData(AudioEncoding.Pcm, lastSegment, ByteVector(buf.slice(0, bytesRead)))
      )
    }
  }

  def inactive: Receive = baseHandler orElse {
    case Recorder.Record =>
      log.debug(s"RECORD")
      //There's data sitting in the buffer from before user sent the Record command, flush it.
      /* NOTE: is it better to start/stop the line? what are performance implications? */
      targetLine.flush()
      become(active)
      self ! Recorder.Next
    case Recorder.Next =>
      log.debug(s"recording endOfSegment, $subscribers")
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
