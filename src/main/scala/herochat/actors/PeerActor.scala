package herochat.actors

import scala.annotation.tailrec
import scala.language.postfixOps
import scala.concurrent.duration._
import scala.math.{max}
import scala.util.{Try,Success,Failure}

import akka.actor.{ActorRef, Props, Actor, PoisonPill, ActorLogging, Kill}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import java.net.{InetAddress, InetSocketAddress}
import java.io.{FileOutputStream}

import za.co.monadic.scopus.{Sf48000}

import javax.sound.sampled.{DataLine, Mixer}

import scodec._
import scodec.bits._
import scodec.codecs._

import herochat.{ChatMessage, User, AuthPayload, Peer, PeerState, AudioEncoding, AudioData, AudioControl}
import herochat.HcCodec._


object PeerActor {
  def props(remoteAddress: InetSocketAddress, socket: ActorRef, initialState: PeerActor.InitialState, localUser: User, localAddress: InetSocketAddress): Props = Props(classOf[PeerActor], remoteAddress, socket, initialState, localUser, localAddress)
  case object Disconnect
  case object PlayAudio
  case object MuteAudio
  case object StoppedSpeaking

  case class SetMixer(lineInfo: DataLine.Info, mixerInfo: Mixer.Info)

  case class LogAudioToFile(filename: String)
  case class CloseLogFile(filename: String)

  trait InitialState
  case object HandshakeInitiator extends InitialState
  case object HandshakeReceiver extends InitialState

  case object HandshakeTimeout
  case object HandshakeComplete

  type HcMessageHandler = OrderedPartialFunction[HcMessage, Unit]
}

class PeerActor(
    val remoteAddress: InetSocketAddress,
    private var socket: ActorRef,
    initialState: PeerActor.InitialState,
    localUser: User,
    localAddress: InetSocketAddress) extends Actor with ActorLogging {
  import context._
  import Tcp._
  import PeerActor._

  val localPort: Int = localAddress.getPort
  /* These variables are assigned when handshake is complete */
  /* TODO: would be better defining these in a closure, or at least using Option */
  var remoteUser: User = null
  var remoteListeningPort: Int = -1
  //state is None before handshake is complete
  var peerState: Option[Peer] = None

  /* Order matters in pattern matching, when defining a HcMessageHandler, make sure to think about this */
  var dynHandlers = scala.collection.mutable.SortedSet[HcMessageHandler]()

  /* create the Opus decoder, and audio player actors */
  //val nameSuffix = "-" + remoteAddress.toString.replace("/", "")
  val decoder = context.actorOf(Decoder.props(Sf48000, 1), "hc-decoder")
  var activeFileWriters = scala.collection.mutable.Map[String, ActorRef]()
  var audioSubscribers = scala.collection.mutable.Set[ActorRef]()
  //this stuff should be conditional
  audioSubscribers += decoder

  /* TODO: error recovery for players
   * TODO: volume saved across mixers?
   */
  var lineInfo: Option[DataLine.Info] = None
  var mixerInfo: Option[Mixer.Info] = None
  var player: Option[ActorRef] = None
  var playerI = 0
  def respawnPlayer(): Unit = {
    player.foreach(_ ! Kill)
    /* TODO: should these be get? */
    player = Some(context.actorOf(AudioPlayer.props(lineInfo.get, mixerInfo.get), s"hc-player-$playerI"))
    decoder ! AddSubscriber(player.get)
    if (peerState.get.muted) {
      player.foreach(_ ! AudioControl.Mute)
    } else {
      player.foreach(_ ! AudioControl.Unmute)
    }
    playerI += 1
  }

  def updateState(newPeerState: Peer): Unit = {
    peerState = Some(newPeerState)
    parent ! PeerState.UpdatePeer(newPeerState)
  }

  /* TODO: variable length timeout */
  system.scheduler.scheduleOnce(5 seconds, self, HandshakeTimeout)
  /* Peers start in one of two states, depending on whether the connection is incoming or outgoing */
  def receive = initialState match  {
    case HandshakeInitiator =>
      log.debug(s"Peer starting in Initiate Mode, connecting to $remoteAddress")
      IO(Tcp) ! Connect(remoteAddress)
      handshakeInitiate
    case HandshakeReceiver =>
      log.debug(s"Peer starting in Reciprocate Mode, connected to $remoteAddress")
      handshakeReciprocate
  }

  //Base case handlers for handshake states
  val basicPreShookReceive: PartialFunction[Any, Unit] = {
    case HandshakeComplete =>
      log.debug(s"Handshake complete, entering dynamic state")
      become(dynamicReceive)
      parent ! PeerState.NewPeer(peerState.get)
    case HandshakeTimeout =>
      log.debug(s"Handshake timed out, disconnecting from remote host")
      self ! Disconnect
    case msg: ConnectionClosed =>
      log.debug(s"connection closed by remote peer, unshook: $msg")
      context stop self
    case Disconnect =>
      log.debug(s"unshook, disconnecting from $remoteAddress")
      socket ! Close
      context stop self
  }

  /* Parses a Authentication message payload
   * Assigns the user and port encoded in packet to remoteUser & remoteListeningPort
   */
  def parseAuthPayload(payload: Vector[AuthPayload.AuthPair]): Unit = {
    /* TODO: find a way to avoid the map form, it seems clumsy */
    val mapForm = AuthPayload.toMap(payload)
    val id = mapForm(AuthPayload.tUUID).right.get.asInstanceOf[AuthPayload.AuthTypeUUID].uuid
    val name = mapForm(AuthPayload.tNickname).right.get.asInstanceOf[AuthPayload.AuthTypeNickname].nickname
    remoteUser = User(id, name)
    remoteListeningPort = mapForm(AuthPayload.tPort).right.get.asInstanceOf[AuthPayload.AuthTypePort].port
    peerState = Some(Peer(remoteUser, false, false, false, 1.0))
  }

  /* Handlers for herochat protocol messages related to handshake procedure */
  val handshakeDisconnectHandler = new HcMessageHandler(0, {
    case HcMessage(MsgTypeShakeDisconnect, msgLength, payload) =>
      log.debug(s"Received Handshake Disconnect message")
      throw new IllegalArgumentException(s"failed handshake procedure")
  })
  /* TODO: Do these need to be defined separately from the handshake receives, they are only used once */
  /* Wait for auth message from remote host; respond with an auth message*/
  val handshakeReciproAuthHandler = new HcMessageHandler(0, {
    case HcMessage(MsgTypeShakeAuth, msgLength, payload) =>
      //decode and validate response from remote host
      val decPayload = AuthPayload.vecCodec.decode(payload.bits).require.value
      parseAuthPayload(decPayload)
      log.debug(s"Received Handshake auth message: $decPayload, sending response")
      val authPayload = HCAuthMessage(localUser.id, localUser.nickname, localPort)
      socket ! Write(ByteString(Codec.encode(authPayload).require.toByteArray))
      //Peer does not complete handshake until it receives signoff from BigBoss
      //handles a race condition where two peers connect to eachother at nearly the same time
      parent ! BigBoss.PeerShook(remoteAddress, remoteUser, self, remoteListeningPort)
  })
  /* Wait for auth message from remote host; handshake is complete when received*/
  val handshakeReceiveAuthHandler = new HcMessageHandler(0, {
    case HcMessage(MsgTypeShakeAuth, msgLength, payload) =>
      val decPayload = AuthPayload.vecCodec.decode(payload.bits).require.value
      parseAuthPayload(decPayload)
      log.debug(s"Received Handshake auth message: $decPayload, handshake complete")
      parent ! BigBoss.PeerShook(remoteAddress, remoteUser, self, remoteListeningPort)
  })

  /* Handshake State, performing handhsake with other peer
   * One peer starts in handshakeInitiate, sends a handshake message to remote peer, moves to handShakeReceive
   * The remote peer starts in handshakeReciprocate, waits to receive a handshake message, than responds with its own
   * handshake message
   */
  def handshakeInitiate: Receive = basicPreShookReceive orElse {
    //I suspect there is some weird race condition where the remote peer connects, then instantly sends a disconnect msg
    //We might not miss that, depending on how underlying libs handle tcp mailboxes
    case Connected(remoteAddress, localAddress) =>
      log.debug(s"Received Connection response: $remoteAddress, $localAddress")
      sender ! Register(self)
      socket = sender
      val auth_load = HCAuthMessage(localUser.id, localUser.nickname, localPort)
      become(handshakeReceive)
      socket ! Write(ByteString(Codec.encode(auth_load).require.toByteArray))
    case CommandFailed(_: Connect) =>
      log.debug(s"failed to connect: $sender")
      context stop self
    case _ @ msg => log.debug(s"handshake-initiate: Bad Msg: $msg, $sender")
  }
  def handshakeReciprocate: Receive = basicPreShookReceive orElse {
    case Received(bytes) =>
      exhaustiveDecode(BitVector(bytes), hcMessage,
        (handshakeReciproAuthHandler orElse
        handshakeDisconnectHandler orElse
        defaultHandler("handshake-recipro"))
      )
    case _ @ msg => log.debug(s"handshake-recipro: Bad Msg: $msg, $sender")
  }
  def handshakeReceive: Receive = basicPreShookReceive orElse {
    case Received(bytes) =>
      exhaustiveDecode(BitVector(bytes), hcMessage,
        (handshakeReceiveAuthHandler orElse
        handshakeDisconnectHandler orElse
        defaultHandler("handshake-recipro"))
      )
    case _ @ msg => log.debug(s"handshake-receive: Bad Msg: $msg, $sender")
  }

  /* Post-Handshake State */

  //HcMessage handlers
  val textHandler = new HcMessageHandler(0, {
    case HcMessage(MsgTypeText, msgLength, payload) =>
      val decoded = utf8.decode(payload.bits).require.value
      log.debug(s"Received HcText message: ${decoded}")
      parent ! ChatMessage(remoteUser, decoded)
  })
  /* These aren't really play/mute, they are more like forwarding encoded audio or ignoring it.
   * forward is a bad name, need to come up with better terminology
   */
  val playAudioHandler = new HcMessageHandler(0, {
    case HcMessage(MsgTypeAudio, msgLength, payload) =>
      if (!peerState.get.speaking) {
        updateState(peerState.get.copy(speaking = true))
      }
      audioSubscribers.foreach(sub => sub ! Codec[AudioData].decode(payload.bits).require.value)
  })
  val muteAudioHandler = new HcMessageHandler(0, {
    case HcMessage(MsgTypeAudio, msgLength, payload) => Unit
  })
  /* TODO: safe handling, not .require.value */
  val pexHandler = new HcMessageHandler(0, {
    //ip4 - list of (ipv4{32-bit}, port{16-bit})
    case HcMessage(MsgTypePex4, msgLength, payload) =>
      val decodedIps = pex4PayloadCodec.decode(payload.bits).require//.value
    //ip6 - list of (ipv6{128-bit}, port{16-bit})
    case HcMessage(MsgTypePex6, msgLength, payload) =>
      val decodedIps = pex6PayloadCodec.decode(payload.bits).require.value
      log.debug(s"received pex: ${decodedIps}")
      decodedIps.foreach((byteAddr: ByteVector, port: Int) =>
        parent ! BigBoss.Connect(new InetSocketAddress(InetAddress.getByAddress(byteAddr.toArray), port))
      )
  })
  val nicknameHandler = new HcMessageHandler(0, {
    case HcMessage(MsgTypeChangeNickname, msgLength, payload) =>
      //change remoteUSer, peerstate
      ()
  })
  /* Handle unrecognized HcMessages, at lowest possible priority */
  val defaultHandler: String => HcMessageHandler = state => new HcMessageHandler(Int.MaxValue, {
    case HcMessage(msgType, msgLength, payload) =>
      log.debug(s"$state: Received HcMessage of unknown type: ${msgType}, dynHandlers: $dynHandlers")
  })

  //Default Post-Handshake Receive function
  val basicShookReceive: PartialFunction[Any, Unit] = {
    /* TODO: Mute, Unmute are more complicated than first glance. Playing audio is a multiple step pipeline:
     * RecvAudio => SendToSubs => DecodeAudio => PlayAudio
     * Breaking the chain at any point will stop audio from being played. For efficiency, the chain
     * should be broken as early as possible, taking into account any logging. e.g. If we are logging
     * the decoded audio stream, we can't stop decoding, otherwise we can't log.
     * this means there are a lot of different states to account for, internal to PeerActor
     */
    /* TODO: log feedback for missing players */
    case PlayAudio =>
      log.debug(s"now playing audio")
      dynHandlers += playAudioHandler
      dynHandlers -= muteAudioHandler
      player.foreach(_ ! AudioControl.Unmute)
      updateState(peerState.get.copy(muted = false))
    case MuteAudio =>
      log.debug(s"now muting audio")
      dynHandlers -= playAudioHandler
      dynHandlers += muteAudioHandler
      player.foreach(_ ! AudioControl.Mute)
      updateState(peerState.get.copy(muted = true))

    case AudioControl.SetVolume(volume) =>
      player.foreach(_ ! AudioControl.SetVolume(volume))
      updateState(peerState.get.copy(volume = volume))

    case SetMixer(newLineInfo, newMixerInfo) =>
      lineInfo = Some(newLineInfo)
      mixerInfo = Some(newMixerInfo)
      respawnPlayer()

    case StoppedSpeaking => updateState(peerState.get.copy(speaking = false))

    case LogAudioToFile(filename) =>
      log.debug(s"logging PCM audio to: $filename")
      /* TODO: include filename in name of actor*/
      val filewriter = context.actorOf(FileWriter.props(), s"pcm-filewriter")
      activeFileWriters(filename) = filewriter
      filewriter ! FileWriter.Open(filename)
      decoder ! AddSubscriber(filewriter)
    case CloseLogFile(filename) =>
      activeFileWriters remove filename match {
        case Some(filewriter) =>
          decoder ! RemoveSubscriber(filewriter)
          filewriter ! FileWriter.Close
          filewriter ! PoisonPill
        case None => log.debug(s"No active fileWriter with name: $filename")
      }
    case c: ConnectionClosed =>
      log.debug(s"connection closed by remote peer, shook: $c")
      parent ! PeerState.RemovePeer(peerState.get)
      context stop self
    case Disconnect =>
      log.debug(s"shook, disconnecting from $remoteAddress")
      socket ! Close
      parent ! PeerState.RemovePeer(peerState.get)
      context stop self
    case hcMsg: HcMessage =>
      if (hcMsg.msgType == MsgTypeAudio && hcMsg.data.bits(7)) {
        log.debug("sending end of segment message")
      }
      socket ! Write(ByteString(Codec.encode(hcMsg).require.toByteArray))
  }

  val dynMute = scala.collection.mutable.SortedSet[HcMessageHandler](textHandler, muteAudioHandler, pexHandler, defaultHandler("dynamic"))
  val dynPlay = scala.collection.mutable.SortedSet[HcMessageHandler](textHandler, playAudioHandler, pexHandler, defaultHandler("dynamic"))
  /* Default state is play */
  dynHandlers = dynPlay

  /* TODO: Definitely need to profile this, make sure it runs well. */
  /*  */
  def exhaustiveDecode[T](bits: BitVector, codec: Codec[T], fun: (T => Unit)): Unit = {
    codec.decode(bits) match {
      case Attempt.Successful(DecodeResult(decoded, rem)) =>
        fun(decoded)
        if (rem.size > 0) {
          exhaustiveDecode(rem, codec, fun)
        }
      case Attempt.Failure(failMsg) => log.debug(s"strange hcbytes: ${bits.decodeUtf8}")
    }
  }

  /* NOTE: doing this fold every time might be slow
   * could do the fold only on state transitions and pass it with become
   */
  /* use exhaustiveDecode because sometimes akka TCP joins multiple TCP messages together
   */
  def dynamicReceive: Receive = {
    basicShookReceive orElse {
      case Received(bytes) =>
        val f = dynHandlers.foldLeft(Map.empty: PartialFunction[HcMessage, Unit])((x, y) => x orElse y)
        exhaustiveDecode(BitVector(bytes), hcMessage, f)
      //would rather this be in a generic function, but anonymous function orElse isn't working
      case _ @ msg =>
        log.debug(s"dynamic, got unhandled message: ${msg.toString}")
    }
  }
}
