package herochat.actors



import scala.util.{Try,Success,Failure}
import scala.collection.mutable.Map
import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.{ActorRef, Props, Actor, Terminated, Kill, ActorLogging}
import akka.io.{IO, Tcp}
import akka.util.{Timeout, ByteString}
import akka.pattern.ask

import javax.sound.sampled.AudioFormat

import java.net.{InetAddress, InetSocketAddress, NetworkInterface}
import java.io.{FileOutputStream}
import java.time.{Instant}
import java.time.temporal.{ChronoUnit}

import za.co.monadic.scopus.{Sf8000, Sf48000, Voip}
import za.co.monadic.scopus.opus.{OpusDecoderShort}

import scodec._
import scodec.bits._
import scodec.codecs._

import za.co.monadic.scopus.{Sf48000}

import herochat.HcCodec._
import herochat.{ChatMessage, User, HcView, Tracker, PeerTable, Peer, PeerState, AudioControl, WavCodec, AudioUtils}
import herochat.SnakeController.ToView

import javax.sound.sampled.{DataLine, TargetDataLine, SourceDataLine, AudioSystem, Mixer}

object BigBoss {
  def props(port: Int, localUser: User, record: Boolean): Props = Props(classOf[BigBoss], port, localUser, record)

  //Don't know about this extends thing, probably a bad idea
  //It would be cool if I could make a macro, so didnt have to write etends every time
  //Another way to go is to wrap the messages, like ToBigBoss(Connect(remoteAddress))
  //  The wrap-method could be problematic
  abstract class BigBossMessage

  //connection commands
  case class Connect(remoteAddress: InetSocketAddress) extends BigBossMessage
  case class Disconnect(remoteAddress: InetSocketAddress) extends BigBossMessage
  case object DisconnectAll extends BigBossMessage

  //Peer creation messages
  case class IncomingConnection(remoteAddress: InetSocketAddress, localAddress: InetSocketAddress, socketRef: ActorRef) extends BigBossMessage
  case class PeerShook(remoteAddress: InetSocketAddress, remoteUser: User, peerRef: ActorRef, remoteListeningPort: Int) extends BigBossMessage

  //chat commands
  case class Shout(msg: String) extends BigBossMessage

  /* TODO: don't need these */
  case object StartRecord extends BigBossMessage
  case object StopRecord extends BigBossMessage

  /* StartSpeaking & StopSpeaking change whether audio data is recorded and sent through pipeline.
   * Mute & UnMute disable & enable, respectively, StartSpeaking */
  case object StartSpeaking extends BigBossMessage
  case object StopSpeaking extends BigBossMessage

  case class SetMuteUser(user: User, setMute: Boolean) extends BigBossMessage
  case class SetDeafenUser(user: User, setDeafen: Boolean) extends BigBossMessage
  case class SetBlockUser(user: User, setBlock: Boolean) extends BigBossMessage
  case class SetServerMuteUser(user: User, setMute: Boolean) extends BigBossMessage
  case class SetServerDeafenUser(user: User, setDeafen: Boolean) extends BigBossMessage
  case class SetVolumeUser(user: User, vol: Double) extends BigBossMessage

  case object CloseFile extends BigBossMessage
  case class ReadFile(filename: String) extends BigBossMessage
  case class OpenFile(filename: String) extends BigBossMessage

  /* Send audio from file to peers */
  case class PlayAudioFile(filename: String, filetype: String)
  /* Record from microphone */
  case class RecordAudioFile(filename: String, filetype: String)

  case object GetSupportedMixers
  case class SetInputMixer(mixer: Mixer.Info)
  case class SetOutputMixer(mixer: Mixer.Info)

  case class DebugInMixerIndex(index: Int)
  case class DebugOutMixerIndex(index: Int)
  //Log Decoded audio
  //case class StartLoggingPeer(addr: InetSocketAddress, filename: String) extends BigBossMessage
  //case class StopLoggingPeer(addr: InetSocketAddress, filename: String) extends BigBossMessage
}

/**
 * Top Level Actor. Creates connections to new peers, parent of all in the system.
 * WRONG! Snake is our lord and master
 *
 * Timed loop for mixing and sending to javax line, every X seconds, take data from buffers, mix together
 * send to javax
 *
 * Invariants we need to enforce: one Peer per BigBoss-BigBoss pair; BigBoss cannot connect to itself
 *
 * TODO: state transition stuff for peers:
 * when we switch into play mode, all peers need to update, all future peers need to start in correct state
 * vice versa
 *
 * TODO: audio probably playing too fast/slow not sure which, decoding from opus at 48k, playing at 44.1k
 */
class BigBoss(listenPort: Int, localUser: User, record: Boolean) extends Actor with ActorLogging {
  import context._
  import Tcp._

  //Audio Recording and Encoding settings
  /**
   * input args: audio encoding, sample rate, sampleSizeInBits, channels, frame size, frame rate, big endian,
   * bufferSize, audio device name, output actor
   */
  val encoding = AudioFormat.Encoding.PCM_SIGNED
  val sampleRate = 44100
  val sampleSize = 16
  val sampleSizeBytes = sampleSize / 8
  val channels = 1
  /* bufSize[InSeconds] = (bufSize / (channels * sampleSizeBytes)) / sampleRate */
  /* TODO: not hardcoded buffer size */
  val bufSize = 24000

  val pcmFmt = WavCodec.PcmFormat(
    encoding,
    channels,
    sampleRate,
    sampleRate * channels * sampleSizeBytes,
    channels * sampleSizeBytes,
    sampleSize
  )
  val format = pcmFmt.toJavax

  //sbt compatibility - Need to change class loader for javax to work
  val cl = classOf[javax.sound.sampled.AudioSystem].getClassLoader
  val old_cl: java.lang.ClassLoader = Thread.currentThread.getContextClassLoader
  Thread.currentThread.setContextClassLoader(cl)
  override def postStop {
    log.debug(s"Stopping, resetting thread context class loader")
    Thread.currentThread.setContextClassLoader(old_cl)
  }

  /* TODO: handle edge case where there are no supported mixers */
  val sourceInfo = new DataLine.Info(classOf[SourceDataLine], format, bufSize)
  val targetInfo = new DataLine.Info(classOf[TargetDataLine], format, bufSize)
  val sourceMixes = AudioUtils.getSupportedMixers(sourceInfo).map(_.getMixerInfo)
  val targetMixes = AudioUtils.getSupportedMixers(targetInfo).map(_.getMixerInfo)
  var sourceMixer = sourceMixes(0)
  var targetMixer = targetMixes(0)

  //IP address we listen for new connections on
  /* TODO: LATER: support modifying what port we listen on during runtime */
  val listenAddress = new InetSocketAddress("::1", listenPort)
  val connHandler = context.actorOf(ConnectionHandler.props(listenPort), "hc-connection-handler")

  /* TODO: support multiple simultaneous file reads/writes */
  val filereader = context.actorOf(FileReader.props(), "hc-filereader")
  val filewriter = context.actorOf(FileWriter.props(), "hc-filewriter")

  val decoder = context.actorOf(Decoder.props(Sf48000, 1), "hc-decoder")
  val encoder = context.actorOf(Encoder.props(20, Sf48000, 1), "hc-encoder")


  /* Table of active peers */
  val peerTable = new PeerTable(self)

  var localPeerState = Peer(localUser, true, false, false, 1.0)
  parent ! ToView(PeerState.NewPeer(localPeerState))

  def updateState(newPeerState: Peer): Unit = {
    localPeerState = newPeerState
    parent ! ToView(PeerState.UpdatePeer(newPeerState))
  }

  def removePreShakePeer(peerTuple: PeerTable.PreShakePeer): Unit = {
    peerTuple._2 ! PeerActor.Disconnect
    peerTable.shakingPeers -= peerTuple
  }
  def removePostShakePeer(peerTuple: PeerTable.PostShakePeer): Unit = {
    peerTuple._2 ! PeerActor.Disconnect
    encoder ! RemoveSubscriber(peerTuple._2)
    peerTable.shookPeers -= peerTuple
    //parent ! HcView.RemovePeer(peerTuple._5)
  }

  /* TODO: replace this */
  var i = 0
  def genPeerName(addr: InetSocketAddress): String = {
    i += 1
    addr.toString.replace("/", "") + "-" + i
  }

  /* Initialize Sound */
  //To play sounds from UI, or direct file reads (debugging)
  /* TODO: refresh player when change output mixer */
  val player = context.actorOf(AudioPlayer.props(sourceInfo, sourceMixer), "hc-player")

  var recI = 0
  var recorder: Option[ActorRef] = None
  if (record) {
    respawnRecorder()
  }
  def respawnRecorder(): Unit = {
    /* Recorders block when recording sound, so kill it immediately, this will send a Terminated
     * msg back to us.
     * TODO: handle terminated, NOTE: use case Terminated(actor) if actor == recorder
     * This will only handle the current active recorder, ignoring the one we just killed
     */
    recorder.foreach(_ ! Kill)
    recorder = Some(context.actorOf(Recorder.props(targetInfo, targetMixer), s"hc-recorder-$recI"))
    recorder.foreach(_ ! AddSubscriber(encoder))
    /* TODO: this should be conditional */
    recorder.foreach(_ ! AddSubscriber(filewriter))
    if (localPeerState.speaking) {
      self ! BigBoss.StartRecord
    }
    recI += 1
  }


  /* Only send Pex messages to the newly connected peer
   */
  def sendPexMessage(newPeer: ActorRef): Unit = {
    val pexAddresses = peerTable.shookPeers.map(x => (ByteVector(x._6.getAddress.getAddress), x._6.getPort))
    log.debug(s"sending PEX to peers: ${pexAddresses}")
    pex6PayloadCodec.encode(pexAddresses.toVector) match {
      case Attempt.Successful(ipList) =>
        val ipListInBytes = ipList.bytes
        val pexMsg = HcMessage(MsgTypePex6, ipListInBytes.length.toInt, ipListInBytes)
        newPeer ! pexMsg
      case x => log.debug(s"Pex Encoding Failure: $x")
    }

  }
  def completeHandshake(remoteAddr: InetSocketAddress, peerRef: ActorRef, remoteUser: User, pexAddr: InetSocketAddress): Unit = {
    log.debug(s"Completing Handshake for: $remoteAddr, $remoteUser, $peerRef, $pexAddr")
    peerRef ! PeerActor.HandshakeComplete
    peerRef ! PeerActor.SetMixer(sourceInfo, sourceMixer)
    context watch peerRef
    peerTable.completeShake(remoteAddr, peerRef, remoteUser, pexAddr)
    encoder ! AddSubscriber(peerRef)
    sendPexMessage(peerRef)
  }

  def receive: Receive = {
    //Outgoing Connection
    case BigBoss.Connect(remoteAddress) =>
      log.debug(s"BigBoss.Connect checking for valid address: $remoteAddress")
      if (peerTable.preShakeVerify(remoteAddress, listenAddress)) {
        log.debug(s"send connection to $remoteAddress")
        val peerRef = context.actorOf(PeerActor.props(remoteAddress, null, PeerActor.HandshakeInitiator, localUser, listenAddress), "hc-peer-out-" + genPeerName(remoteAddress))
        peerTable.shakingPeers += ((remoteAddress, peerRef, true, Instant.now))
      } else {
        log.debug(s"$remoteAddress is an invalid connection address")
      }

    //Incoming Connection, message received from connectionHandler
    case BigBoss.IncomingConnection(remoteAddress, localAddress, socketRef) =>
      log.debug(s"checking incoming peer: $remoteAddress, $localAddress")
      //check that this peer isn't ourselves, and that we aren't already connected to it
      if (!peerTable.preShakeVerify(remoteAddress, listenAddress)) {
        log.debug(s"Duplicate/Self-connected peer incoming: $remoteAddress, $localAddress")
        socketRef ! Write(ByteString(HcDisconnect.toByteArray))
      } else {
        log.debug(s"Creating Peer actor: $remoteAddress, $localAddress")
        val peerRef = context.actorOf(PeerActor.props(remoteAddress, socketRef, PeerActor.HandshakeReceiver, localUser, listenAddress), "hc-peer-in-" + genPeerName(remoteAddress))
        socketRef ! Register(peerRef)
        peerTable.shakingPeers += ((remoteAddress, peerRef, false, Instant.now))
      }

    /* TODO: what if we attempt two handshakes to same peer */
    case BigBoss.PeerShook(remoteAddress, remoteUser, peerRef, remoteListeningPort) =>
      val remotePexAddr = new InetSocketAddress(remoteAddress.getAddress, remoteListeningPort)
      log.debug(s"Received shook notice from: $peerRef: $remoteAddress, $remoteUser, $remoteListeningPort")
      if (peerTable.postShakeVerify(remoteAddress, remotePexAddr)) {
        completeHandshake(remoteAddress, peerRef, remoteUser, remotePexAddr)
      } else {
        /* TODO: evaluate this logic, simultaneous connections don't generally work, is it worth the
         * complexity to save a few odd scenarios?
         */
        /* Handle edge case where we connect to remote at same time they connect to us.
         * deterministically close one of the connections, using the user id of the initiator
         * lower user id's have priority
         */
        /* NOTE: simultaneous connections is causing some issues, so for now, going to change PEX.
         * It will be the responsibilty of the recipient of the connection to send a list
         */
        /* TODO: scala-fy java.time */
        //get conflicting peer
        log.debug(s"peer conflict: $remoteAddress, $remotePexAddr, ${peerTable.shakingPeers}, ${peerTable.shookPeers}")
        val newPeer = peerTable.getUnShookByPeerRef(peerRef).get
        val oldPeer = (peerTable.getShookByAddr(remoteAddress) orElse peerTable.getShookByPexAddr(remotePexAddr)).get
        //check if shook peer is older than 5 seconds
        /* TODO: document this, put in function */
        if (oldPeer._4.plus(1, ChronoUnit.SECONDS).isBefore(Instant.now)) {
          log.debug(s"pconf 1: ${oldPeer}, ${newPeer}")
          removePreShakePeer(newPeer)
        } else if (oldPeer._3 == newPeer._3) {
          log.debug(s"pconf 2: ${oldPeer}, ${newPeer}")
          removePreShakePeer(newPeer)
        } else {
          if (newPeer._3) {
            if (localUser.id < remoteUser.id) {
              log.debug(s"pconf 3: ${oldPeer}, ${newPeer}")
              removePostShakePeer(oldPeer)
              completeHandshake(remoteAddress, peerRef, remoteUser, remotePexAddr)
            } else {
              log.debug(s"pconf 4: ${oldPeer}, ${newPeer}")
              removePreShakePeer(newPeer)
            }
          } else if (oldPeer._3) {
            if (localUser.id < remoteUser.id) {
              log.debug(s"pconf 5: ${oldPeer}, ${newPeer}")
              removePreShakePeer(newPeer)
            } else {
              log.debug(s"pconf 6: ${oldPeer}, ${newPeer}")
              removePostShakePeer(oldPeer)
              completeHandshake(remoteAddress, peerRef, remoteUser, remotePexAddr)
            }
          }
        }
      }
      log.debug(s"peer shook OWOR: $peerRef, ${peerTable.shakingPeers}, ${peerTable.shookPeers}")

    /* TODO: are there race conditions here, we don't disconnect from some very young peers */
    case BigBoss.DisconnectAll =>
      peerTable.shakingPeers.foreach( _._2 ! PeerActor.Disconnect )
      peerTable.shookPeers.foreach( _._2 ! PeerActor.Disconnect )
    case BigBoss.Disconnect(remoteAddress) =>
      log.debug(s"disconnecting from every peer at $remoteAddress, ${peerTable.shakingPeers}, ${peerTable.shookPeers}, ${peerTable.getIsShookByAddr(remoteAddress)}, ${peerTable.getShookByPexAddr(remoteAddress)}")
      peerTable.getIsShookByAddr(remoteAddress).foreach(x => x match {
        case Left(peerTuple) =>
          log.debug(s"disconnecting unshook out peer $remoteAddress")
          removePreShakePeer(peerTuple)
        case Right(peerTuple) =>
          log.debug(s"disconnecting shook out peer $remoteAddress")
          removePostShakePeer(peerTuple)
      })
      peerTable.getShookByPexAddr(remoteAddress).foreach(peerTuple => {
        log.debug(s"disconnecting shook in peer $remoteAddress")
        removePostShakePeer(peerTuple)
      })
    case Terminated(peerRef) =>
      log.debug(s"$peerRef terminated")
      //Use the general remove procedure, sending out a Disconnect dead letter
      peerTable.getIsShookByARef(peerRef).foreach(_ match {
        case Left(peerTuple) =>
          removePreShakePeer(peerTuple)
        case Right(peerTuple) =>
          removePostShakePeer(peerTuple)
      })

    /* Peers alert us of changes in their state, forward to the controller */
    case msg: PeerState.PeerStateChange => parent ! ToView(msg)

    //public chat commands
    case BigBoss.Shout(msg) =>
      log.debug(s"Sending msgs to: ${peerTable.shookPeers}")
      utf8.encode(msg) match {
        case Attempt.Successful(utf8Bits) =>
          val utf8Bytes = utf8Bits.bytes
          val hcMsg = HcMessage(MsgTypeText, utf8Bytes.length.toInt, utf8Bytes)
          peerTable.shookPeers.foreach(peer => peer._2 ! hcMsg)
        case x =>
          log.debug(s"Failed encoding Message to utf8: $msg, $x")
      }

    //Received a chat message
    /* TODO: replace this with meaning */
    case msg: ChatMessage => parent ! msg

    case BigBoss.StartRecord =>
      log.debug(s"starting record: $sender")
      recorder.foreach(_ ! Recorder.Record)
    case BigBoss.StopRecord =>
      recorder.foreach(_ ! Recorder.Pause)
      log.debug(s"pausing record: $sender")

    case BigBoss.StartSpeaking if !localPeerState.muted =>
      /* TODO: this is conditional based on logging */
      /* TODO: filename based on name */
      //filewriter ! FileWriter.OpenWav("test_data/output_test.wav", pcmFmt)

      self ! BigBoss.StartRecord
      updateState(localPeerState.copy(speaking = true))
    case BigBoss.StopSpeaking if !localPeerState.muted =>
      self ! BigBoss.StopRecord
      updateState(localPeerState.copy(speaking = false))

    case BigBoss.SetMuteUser(user, setMute) =>
      peerTable.getShookByUser(user) match {
        case Some(remotePeerState) =>
          log.debug(s"Setting mute on user: $user to $setMute")
          if (setMute) {
            remotePeerState._2 ! PeerActor.MuteAudio
          } else {
            remotePeerState._2 ! PeerActor.PlayAudio
          }
        case None =>
          log.debug(s"Setting mute on self to $setMute")
          updateState(localPeerState.copy(muted = setMute))
      }
    case BigBoss.SetDeafenUser(user, setDeafen) =>
      if (user == localUser) {
        if (setDeafen) {
          peerTable.shookPeers.foreach(_._2 ! PeerActor.MuteAudio)
        } else {
          peerTable.shookPeers.foreach(_._2 ! PeerActor.PlayAudio)
        }
        updateState(localPeerState.copy(deafened = setDeafen))
      }

    case BigBoss.SetBlockUser(user, setBlock) =>
      log.debug(s"Unimplemented: Blocking User: $user, $setBlock")
    case BigBoss.SetServerMuteUser(user, setMute) =>
      log.debug(s"Unimplemented: Server Muting User: $user, $setMute")
    case BigBoss.SetServerDeafenUser(user, setDeafen) =>
      log.debug(s"Unimplemented: Server Deafening User: $user, $setDeafen")

    /* Volume control */
    case BigBoss.SetVolumeUser(user, volume) =>
      peerTable.getShookByUser(user).foreach(_._2 ! AudioControl.SetVolume(volume))

    /* Mixer Options */
    case BigBoss.GetSupportedMixers =>
      val cl = classOf[javax.sound.sampled.AudioSystem].getClassLoader
      val old_cl: java.lang.ClassLoader = Thread.currentThread.getContextClassLoader
      Thread.currentThread.setContextClassLoader(cl)

      val sourceMixers = AudioUtils.getSupportedMixers(sourceInfo).map(_.getMixerInfo)
      val targetMixers = AudioUtils.getSupportedMixers(targetInfo).map(_.getMixerInfo)

      log.debug(s"Sending Source Mixers: ${sourceMixers.mkString(" :: ")}")
      log.debug(s"Sending Target Mixers: ${targetMixers.mkString(" :: ")}")

      parent ! ToView(HcView.OutputMixers(sourceMixer, sourceMixers))
      parent ! ToView(HcView.InputMixers(targetMixer, targetMixers))

      Thread.currentThread.setContextClassLoader(old_cl)
    case BigBoss.SetInputMixer(mixer) =>
      /* TODO: make sure that if we change mixer in bigboss, it is updated in the GUI */
      log.debug(s"new input mixer recved: $mixer, $targetMixer")
      if (mixer != targetMixer) {
        targetMixer = mixer
        respawnRecorder()
      }
    case BigBoss.SetOutputMixer(mixer) =>
      log.debug(s"new output mixer recved: $mixer")
      if (mixer != sourceMixer) {
        sourceMixer = mixer
        peerTable.shookPeers.foreach( _._2 ! PeerActor.SetMixer(sourceInfo, sourceMixer) )
      }

    case BigBoss.ReadFile(filename) =>
      filereader ! FileReader.Open(filename)
    case BigBoss.OpenFile(filename) =>
      filewriter ! FileWriter.Open(filename)
    case BigBoss.CloseFile =>
      filewriter ! FileWriter.Close

    /* Play a file, set speaking until file ends.
     * User is allowed to record own voice, so speaking can continue after file ends
     * That's complicated..
     */
    case BigBoss.PlayAudioFile(filename, filetype) =>
      if (filetype == "wav") {
        filereader ! AddSubscriber(encoder)
        filereader ! FileReader.OpenWav(filename, pcmFmt)
      } else {
        log.debug(s"Unsupported Filetype: $filename, $filetype")
      }

    case BigBoss.RecordAudioFile(filename, filetype) =>
      ()


    case BigBoss.DebugInMixerIndex(index) =>
      val cl = classOf[javax.sound.sampled.AudioSystem].getClassLoader
      val old_cl: java.lang.ClassLoader = Thread.currentThread.getContextClassLoader
      Thread.currentThread.setContextClassLoader(cl)

      val targetMixers = AudioUtils.getSupportedMixers(targetInfo).map(_.getMixerInfo)
      self ! BigBoss.SetInputMixer(targetMixers(index))
      log.debug(s"Changing input mixer to index $index: ${targetMixers(index)}")

      Thread.currentThread.setContextClassLoader(old_cl)
    case BigBoss.DebugOutMixerIndex(index) =>
      val cl = classOf[javax.sound.sampled.AudioSystem].getClassLoader
      val old_cl: java.lang.ClassLoader = Thread.currentThread.getContextClassLoader
      Thread.currentThread.setContextClassLoader(cl)

      val sourceMixers = AudioUtils.getSupportedMixers(sourceInfo).map(_.getMixerInfo)
      self ! BigBoss.SetOutputMixer(sourceMixers(index))
      log.debug(s"Changing output mixer to index $index: ${sourceMixers(index)}")

      Thread.currentThread.setContextClassLoader(old_cl)

    /*
    case BigBoss.DebugPCMFromFile(filename) =>
      filereader ! AddSubscriber(encoder)
      filereader ! FileReader.Open(filename)
    case BigBoss.DebugPCMFromWavFile(filename) =>
      filereader ! AddSubscriber(encoder)
      filereader ! FileReader.OpenWav(filename, pcmFmt)
    case BigBoss.DebugRecordToFile(filename) =>
      //Record PCM data from javax, write it to a file
      recorder.foreach(_ ! AddSubscriber(filewriter))
      filewriter ! FileWriter.Open(filename)
      self ! BigBoss.StartRecord
    */
    case _ @ msg => log.debug(s"Bad Msg: $msg")
  }
}
