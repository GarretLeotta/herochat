package herochat.actors



import scala.util.{Try,Success,Failure}
import scala.collection.mutable.Map
import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.{ActorRef, Props, Actor, Terminated, ActorLogging}
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
import herochat.{ChatMessage, User, HcView, Tracker, PeerTable, Peer, PeerState, AudioControl}
import herochat.SnakeController.ToView


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
  //chat commands
  case class Shout(msg: String) extends BigBossMessage

  case class ReadFile(filename: String) extends BigBossMessage
  case class OpenFile(filename: String) extends BigBossMessage
  case object CloseFile extends BigBossMessage
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

  //Peer creation messages
  case class IncomingConnection(remoteAddress: InetSocketAddress, localAddress: InetSocketAddress, socketRef: ActorRef) extends BigBossMessage
  case class PeerShook(remoteAddress: InetSocketAddress, remoteUser: User, peerRef: ActorRef, remoteListeningPort: Int) extends BigBossMessage

  //Log Decoded audio
  case class StartLoggingPeer(addr: InetSocketAddress, filename: String) extends BigBossMessage
  case class StopLoggingPeer(addr: InetSocketAddress, filename: String) extends BigBossMessage

  //Debug commands
  case class DebugReadFile(filename: String) extends BigBossMessage
  case class DebugPlayFromFile(filename: String) extends BigBossMessage
  case class DebugRecordToFile(filename: String) extends BigBossMessage
  case class DebugPCMFromFile(filename: String) extends BigBossMessage
  case class DebugOpusFromFile(filename: String) extends BigBossMessage
  case class DebugPCMToFile(filename: String) extends BigBossMessage
  case class DebugOpusToFile(filename: String) extends BigBossMessage
  case object DebugMuteAllPeers extends BigBossMessage
  case object DebugPlayAllPeers extends BigBossMessage
  case class DebugLogAllAudio(filePrefix: String) extends BigBossMessage
  case class DebugLogAudioFrom(remoteUser: User, filename: String) extends BigBossMessage
  case class DebugStopLogAudioFrom(remoteUser: User, filename: String) extends BigBossMessage
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
  val channels = 1
  val frameSize = sampleSize * channels / 8
  val frameRate = sampleRate
  val bigEndian = false
  /* bufSize[InSeconds] = (bufSize / frameSize) / sampleRate */
  val bufSize = 24000
  val inDeviceName = "Microphone (INSIGNIA USB MIC De"

  val format = new AudioFormat(encoding, sampleRate, sampleSize, channels, frameSize, frameRate, bigEndian)

  //IP address we listen for new connections on
  /* TODO: LATER: support modifying what port we listen on during runtime */
  val listenAddress = new InetSocketAddress("::1", listenPort)
  val connHandler = context.actorOf(ConnectionHandler.props(listenPort), "hc-connection-handler")

  /* TODO: support multiple file reads/writes */
  val filereader = context.actorOf(FileReader.props(), "hc-filereader")
  val filewriter = context.actorOf(FileWriter.props(), "hc-filewriter")

  //For debug purposes, if we want to play sound directly from a file
  val player = context.actorOf(AudioPlayer.props(format, bufSize), "hc-player")
  val decoder = context.actorOf(Decoder.props(Sf48000, 1), "hc-decoder")

  var recorder: Option[ActorRef] = None
  val encoder = context.actorOf(Encoder.props(20, Sf48000, 1), "hc-encoder")
  if (record) {
    recorder = Some(context.actorOf(Recorder.props(format, bufSize, inDeviceName), "hc-recorder"))
    recorder.foreach(_ ! AddSubscriber(encoder))
    /* This is conditional */
    recorder.foreach(_ ! AddSubscriber(filewriter))
  }

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

  /* Only send Pex messages to the newly connected peer
   */
  def sendPexMessage(newPeer: ActorRef): Unit = {
    /* TODO: filter PEX, improve the procedure, improve codec for InetSocketAddress so I don't need this mess */
    val pexAddresses = peerTable.shookPeers.map(x => (ByteVector(x._6.getAddress.getAddress), x._6.getPort))
    log.debug(s"sending PEX to peers: ${pexAddresses}")
    val ipList = pex6PayloadCodec.encode(pexAddresses.toVector).require.bytes
    val pexMsg = HcMessage(MsgTypePex6, ipList.length.toInt, ipList)
    //peerTable.shookPeers.foreach(peer => peer._2 ! pexMsg)
    newPeer ! pexMsg
  }
  def completeHandshake(remoteAddr: InetSocketAddress, peerRef: ActorRef, remoteUser: User, pexAddr: InetSocketAddress): Unit = {
    log.debug(s"Completing Handshake for: $remoteAddr, $remoteUser, $peerRef, $pexAddr")
    peerRef ! PeerActor.HandshakeComplete
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
        val peerRef = context.actorOf(PeerActor.props(remoteAddress, null, PeerActor.HandshakeInitiator, format, bufSize, localUser, listenAddress), "hc-peer-out-" + genPeerName(remoteAddress))
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
        socketRef ! Write(ByteString(Codec.encode(HcMessage(MsgTypeShakeDisconnect, 0, hex"")).require.toByteArray))
      } else {
        log.debug(s"Creating Peer actor: $remoteAddress, $localAddress")
        val peerRef = context.actorOf(PeerActor.props(remoteAddress, socketRef, PeerActor.HandshakeReceiver, format, bufSize, localUser, listenAddress), "hc-peer-in-" + genPeerName(remoteAddress))
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
      val utf8Encoded = utf8.encode(msg).require.bytes
      val hcMsg = HcMessage(MsgTypeText, utf8Encoded.length.toInt, utf8Encoded)
      peerTable.shookPeers.foreach(peer => peer._2 ! hcMsg)
    //Received a chat message
    case msg: ChatMessage => parent ! msg

    case BigBoss.ReadFile(filename) =>
      filereader ! FileReader.Open(filename)
    case BigBoss.OpenFile(filename) =>
      filewriter ! FileWriter.Open(filename)
    case BigBoss.CloseFile =>
      filewriter ! FileWriter.Close

    case BigBoss.StartRecord =>
      log.debug(s"starting record: $sender")
      recorder.foreach(_ ! Recorder.Record)
    case BigBoss.StopRecord =>
      recorder.foreach(_ ! Recorder.Pause)
      log.debug(s"pausing record: $sender")

    case BigBoss.StartSpeaking if !localPeerState.muted =>
      /* TODO: this is conditional based on logging */
      /* TODO: filename based on name */
      filewriter ! FileWriter.Open("test_data/output_test.pcm")

      self ! BigBoss.StartRecord
      updateState(localPeerState.copy(speaking = true))
    case BigBoss.StopSpeaking if !localPeerState.muted =>
      self ! BigBoss.StopRecord
      updateState(localPeerState.copy(speaking = false))


    /* TODO: mute should stop transmit if user is currently speaking */
    /* TODO: unmute */
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


    //Debug Messages
    case BigBoss.DebugReadFile(filename) =>
      filereader ! FileReader.Open(filename)
    
    case BigBoss.DebugPCMFromFile(filename) =>
      filereader ! AddSubscriber(encoder)
      filereader ! FileReader.Open(filename)



    case BigBoss.DebugPlayFromFile(filename) =>
      //read PCM data from a file, play it through javax
      filereader ! AddSubscriber(player)
      filereader ! FileReader.Open(filename)
    case BigBoss.DebugRecordToFile(filename) =>
      //Record PCM data from javax, write it to a file
      recorder.foreach(_ ! AddSubscriber(filewriter))
      filewriter ! FileWriter.Open(filename)
      self ! BigBoss.StartRecord
    case BigBoss.DebugOpusFromFile(filename) =>
      //read Opus data from a file, send it to peers
      ()
    case BigBoss.DebugPCMToFile(filename) =>
      //read Opus data from peers, decode it, write it to a file
      ()
    case BigBoss.DebugOpusToFile(filename) =>
      //read Opus data from peers, write it to a file
      //file will be a mess, bunch of audio messed up together
    case BigBoss.DebugLogAllAudio(filePrefix) =>
      ()
    case BigBoss.DebugLogAudioFrom(remoteUser, filename) =>
      peerTable.getShookByUser(remoteUser) match {
        case Some(peerTuple) => peerTuple._2 ! PeerActor.LogAudioToFile(filename)
        case None => ()
      }
    case BigBoss.DebugStopLogAudioFrom(remoteUser, filename) =>
      peerTable.getShookByUser(remoteUser) match {
        case Some(peerTuple) => peerTuple._2 ! PeerActor.CloseLogFile(filename)
        case None => ()
      }
    case _ @ msg => log.debug(s"Bad Msg: $msg")
  }

  //debug
  override def postStop {
    log.debug(s"Stopping $self")
  }
}
