package herochat.actors

import scala.annotation.tailrec
import scala.language.postfixOps
import scala.collection.mutable.Buffer
import scala.concurrent.duration._
import scala.math.{max}
import scala.util.{Try,Success,Failure}

import akka.actor.{ActorRef, Props, Actor, PoisonPill, ActorLogging, Kill}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import java.net.{InetSocketAddress, InetAddress, Inet6Address}
import java.io.{FileOutputStream}

import za.co.monadic.scopus.{Sf48000}

import javax.sound.sampled.{DataLine, Mixer}

import scodec._
import scodec.bits._
import scodec.codecs._

import herochat.{Peer, PeerState, AudioEncoding, AudioData, AudioControl}
import herochat.HcCodec._


object PeerActor {
  def props(remoteAddress: InetSocketAddress, socket: ActorRef, initialState: PeerActor.InitialState, localPeer: Peer, localAddress: InetSocketAddress): Props = Props(classOf[PeerActor], remoteAddress, socket, initialState, localPeer, localAddress)
  case object Disconnect
  case object PlayAudio
  case object MuteAudio
  case object StoppedSpeaking

  case class SetMixer(lineInfo: DataLine.Info, mixerInfo: Mixer.Info)
  case class SetListenAddress(addr: InetSocketAddress)

  case class LogAudioToFile(filename: String)
  case class CloseLogFile(filename: String)

  trait InitialState
  case object HandshakeInitiator extends InitialState
  case object HandshakeReceiver extends InitialState

  case object HandshakeTimeout
  case class HandshakeComplete(savedPeerState: Option[Peer])

  type HcMessageHandler = OrderedPartialFunction[HcMessage, Unit]
}



class PeerActor(
    val remoteAddress: InetSocketAddress,
    var socket: ActorRef,
    initialState: PeerActor.InitialState,
    var localPeer: Peer,
    var localAddress: InetSocketAddress,
  ) extends Actor with ActorLogging {
  import context._
  import Tcp._
  import PeerActor._

  /* These variables are assigned when handshake is complete */
  /* TODO: would be better defining these in a closure, or at least using Option */
  //state is None before handshake is complete
  var peerState: Option[Peer] = None

  /* Order matters in pattern matching, when defining a HcMessageHandler, make sure to think about this */
  /* TODO: this sucks, move away from this (double-mutability) maybe to FSM */
  var dynHandlers = scala.collection.mutable.SortedSet[HcMessageHandler]()

  /* create the Opus decoder, and audio player actors */
  //val nameSuffix = "-" + remoteAddress.toString.replace("/", "")
  val decoder = context.actorOf(Decoder.props(Sf48000, 1), "hc-decoder")
  val activeFileWriters = scala.collection.mutable.Map[String, ActorRef]()
  val audioSubscribers = scala.collection.mutable.Set[ActorRef]()
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
    player.foreach(_ ! AudioControl.SetVolume(peerState.get.volume))
    if (peerState.get.muted) {
      player.foreach(_ ! AudioControl.Mute)
    } else {
      player.foreach(_ ! AudioControl.Unmute)
    }
    playerI += 1
  }

  def combineSavedAndNewState(savedPeerState: Peer, newPeerState: Peer): Peer = {
    Peer(
      newPeerState.id,
      newPeerState.nickname,
      savedPeerState.muted,
      savedPeerState.deafened,
      savedPeerState.speaking,
      savedPeerState.volume,
    )
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
      /* Timeout?? */
      //Connect(remoteAddress: InetSocketAddress, localAddress: Option[InetSocketAddress] = None, options: Traversable[SocketOption] = Nil, timeout: Option[FiniteDuration] = None, pullMode: Boolean = false) extends Command with Product with Serializable
      handshakeInitiate
    case HandshakeReceiver =>
      log.debug(s"Peer starting in Reciprocate Mode, connected to $remoteAddress")
      handshakeReciprocate
  }

  //Base case handlers for handshake states
  val basicPreShookReceive: PartialFunction[Any, Unit] = {
    case HandshakeComplete(savedPeerState) =>
      log.debug(s"Handshake complete, entering dynamic state")
      become(dynamicReceive)
      savedPeerState.foreach{ x: Peer => peerState = Some(combineSavedAndNewState(x, peerState.get)) }
      //send any stored messages from race-condition state
      if (!handshake_race_msgs.isEmpty) {
        log.debug(s"Resending ${handshake_race_msgs.length} messages")
        handshake_race_msgs.foreach{case (orig_sender, msg) => self.tell(msg, orig_sender)}
      }
      parent ! PeerState.NewPeer(peerState.get)
    case HandshakeTimeout =>
      parent ! BigBoss.ShowErrorMessage("Connection Handshake Timed Out")
      log.debug(s"Handshake timed out, disconnecting from remote host")
      self ! Disconnect
    case Disconnect =>
      log.debug(s"unshook, disconnecting from $remoteAddress")
      socket ! Close
      context stop self
    /*
    case Aborted => () //Aborted in response to Abort
    case Closed => () //Normal close in response to Close
    case ConfirmedClosed => () //Closed in response to ConfirmedClosed (sent to both)
    case ErrorClosed => () // IO ERROR
    case PeerClosed => () // Closed our half of connection
    */
  }

  /* Handlers for herochat protocol messages related to handshake procedure */
  val handshakeDisconnectHandler = new HcMessageHandler(0, {
    case HcShakeDisconnectMessage =>
      log.debug(s"Received Handshake Disconnect message")
      throw new IllegalArgumentException(s"failed handshake procedure")
  })
  /* TODO: Do these need to be defined separately from the handshake receives, they are only used once */
  /* Wait for auth message from remote host; respond with an auth message*/
  val handshakeReciproAuthHandler = new HcMessageHandler(0, {
    case HcAuthMessage(uuid, port, nickname) =>
      /* Default Peer State, BigBoss sends Mute/Deafened/Volume State from file on Handshakecomplete */
      peerState = Some(Peer(uuid, nickname, false, false, false, 1.0))
      log.debug(s"Received Handshake auth message: $uuid, $port, $nickname, sending response")
      val response = HcAuthMessage(localPeer.id, localAddress.getPort, localPeer.nickname)
      socket ! Write(ByteString(Codec[HcMessage].encode(response).require.toByteArray))

      //Peer does not complete handshake until it receives signoff from BigBoss
      //handles a race condition where two peers connect to eachother at nearly the same time
      parent ! BigBoss.PeerShook(remoteAddress, peerState.get.id, self, new InetSocketAddress(remoteAddress.getAddress, port))
      /* Store messages received until BigBoss responds with HandshakeComplete. Handles a race
       * condition where this Peer receives a Pex message before handshake fully completes */
      become(handshakeStorage)
  })
  /* Wait for auth message from remote host; handshake is complete when received*/
  val handshakeReceiveAuthHandler = new HcMessageHandler(0, {
    case HcAuthMessage(uuid, port, nickname) =>
      /* Default Peer State, BigBoss sends Mute/Deafened/Volume State from file on Handshakecomplete */
      peerState = Some(Peer(uuid, nickname, false, false, false, 1.0))
      log.debug(s"Received Handshake auth message: $uuid, $port, $nickname, handshake complete")
      parent ! BigBoss.PeerShook(remoteAddress, peerState.get.id, self, new InetSocketAddress(remoteAddress.getAddress, port))
      become(handshakeStorage)
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
      val authMsg = HcAuthMessage(localPeer.id, localAddress.getPort, localPeer.nickname)
      socket ! Write(ByteString(Codec[HcMessage].encode(authMsg).require.toByteArray))
      become(handshakeReceive)
    case CommandFailed(_: Connect) =>
      log.debug(s"failed to connect: $sender")
      parent ! BigBoss.ShowErrorMessage("Couldn't connect to remote host")
      context stop self
    case _ @ msg => log.debug(s"handshake-initiate: Bad Msg: $msg, $sender")
  }
  def handshakeReciprocate: Receive = basicPreShookReceive orElse {
    case Received(bytes) =>
      exhaustiveDecode(BitVector(bytes), Codec[HcMessage],
        (handshakeReciproAuthHandler orElse
        handshakeDisconnectHandler orElse
        defaultHandler("handshake-recipro"))
      )
    case _ @ msg => log.debug(s"handshake-recipro: Bad Msg: $msg, $sender")
  }
  def handshakeReceive: Receive = basicPreShookReceive orElse {
    case Received(bytes) =>
      exhaustiveDecode(BitVector(bytes), Codec[HcMessage],
        (handshakeReceiveAuthHandler orElse
        handshakeDisconnectHandler orElse
        defaultHandler("handshake-receive"))
      )
    case _ @ msg => log.debug(s"handshake-receive: Bad Msg: $msg, $sender")
  }
  //Store messages, then send out after handshake complete
  /* TODO: this could be exploited to store a bunch of messages and use a lot of memory.
   * In general, need to prevent exploitation by bad actors. */
  val handshake_race_msgs = Buffer[Tuple2[ActorRef, Any]]()
  def handshakeStorage: Receive = basicPreShookReceive orElse {
    case msg @ Received(bytes) =>
      log.debug(s"Storing message")
      handshake_race_msgs += ((sender, msg))
    case _ @ msg => log.debug(s"handshake-recipro: Bad Msg: $msg, $sender")
  }

  /* Post-Handshake State */

  //HcMessage handlers
  val textHandler = new HcMessageHandler(0, {
    case HcTextMessage(timestamp, content) =>
      log.debug(s"Received HcText message: $timestamp, $content")
      parent ! BigBoss.ReceivedMessage(peerState.get.id, content)
  })
  /* These aren't really play/mute, they are more like forwarding encoded audio or ignoring it.
   * forward is a bad name, need to come up with better terminology
   */
  val playAudioHandler = new HcMessageHandler(0, {
    case HcAudioMessage(audio) =>
      if (!peerState.get.speaking) {
        updateState(peerState.get.copy(speaking = true))
      }
      audioSubscribers.foreach(sub => sub ! audio)
  })
  val muteAudioHandler = new HcMessageHandler(0, {
    case HcAudioMessage(audio) => Unit
  })
  /* TODO: safe handling, not .require.value */
  val pexHandler = new HcMessageHandler(0, {
    //ip4 - list of (ipv4{32-bit}, port{16-bit})
    case HcPex4Message(addresses) => Unit
    //ip6 - list of (ipv6{128-bit}, port{16-bit})
    case HcPex6Message(addresses) =>
      log.debug(s"received pex: ${addresses}")
      addresses.foreach((address: InetAddress, port: Int) =>
        parent ! BigBoss.Connect(new InetSocketAddress(address, port))
      )
    case HcPexChangeAddressMessage(addr, port) =>
      BigBoss.UpdatePeerPexAddress(peerState.get.id, new InetSocketAddress(addr, port))
  })
  val nicknameHandler = new HcMessageHandler(0, {
    case HcChangeNicknameMessage(newName) =>
      updateState(peerState.get.copy(nickname = newName))
  })
  /* Handle unrecognized HcMessages, at lowest possible priority */
  val defaultHandler: String => HcMessageHandler = state => new HcMessageHandler(Int.MaxValue, {
    case msg: HcMessage =>
      log.debug(s"$state: Received HcMessage of unknown type: $msg, dynHandlers: $dynHandlers")
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

    case SetListenAddress(addr) =>
      localAddress = addr
      sendHcMessage(HcPexChangeAddressMessage(addr.getAddress.asInstanceOf[Inet6Address], addr.getPort))

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
      //sendHcMessage(HcMessage(MsgTypePing, 0, hex""))
      socket ! Close
      parent ! PeerState.RemovePeer(peerState.get)
      context stop self
    case hcMsg: HcMessage =>
      sendHcMessage(hcMsg)
  }

  def sendHcMessage(hcMsg: HcMessage): Unit = {
    socket ! Write(ByteString(Codec[HcMessage].encode(hcMsg).require.toByteArray))
  }

  val dynMute = scala.collection.mutable.SortedSet[HcMessageHandler](textHandler, muteAudioHandler, pexHandler, defaultHandler("dynamic"))
  val dynPlay = scala.collection.mutable.SortedSet[HcMessageHandler](textHandler, playAudioHandler, pexHandler, nicknameHandler, defaultHandler("dynamic"))
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
        exhaustiveDecode(BitVector(bytes), Codec[HcMessage], f)
      //would rather this be in a generic function, but anonymous function orElse isn't working
      case _ @ msg =>
        log.debug(s"dynamic, got unhandled message: ${msg.toString}")
    }
  }
}
