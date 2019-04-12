package herochat.actors



import scala.util.{Try,Success,Failure}
import scala.collection.mutable.Map
import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.{ActorRef, Props, Actor, Terminated, Kill, PoisonPill, ActorLogging}
import akka.io.{IO, Tcp}
import akka.util.{Timeout, ByteString}
import akka.pattern.ask

import javax.sound.sampled.AudioFormat

import java.net.{InetAddress, Inet6Address, InetSocketAddress, NetworkInterface}
import java.io.{FileOutputStream}
import java.time.{Instant}
import java.time.temporal.{ChronoUnit}
import java.util.UUID

import za.co.monadic.scopus.{Sf8000, Sf48000, Voip}
import za.co.monadic.scopus.opus.{OpusDecoderShort}

import scodec._
import scodec.bits._
import scodec.codecs._

import za.co.monadic.scopus.{Sf48000}

import herochat.HcCodec._
import herochat.{Settings, HcView, Tracker, PeerTable, Peer, PeerState, AudioControl, WavCodec, AudioUtils}
import herochat.SnakeController.ToView
import herochat.ui.{Toast}

import javax.sound.sampled.{DataLine, TargetDataLine, SourceDataLine, AudioSystem, Mixer}


object Propagate {
  //RequestChange => called from UI, validate change
  //OnChange => Update our state, send message back to UI
  //Message case class for Receive

  trait Propagatable[T] {
    def changeProperty(newVal: T): ChangeProperty[T]
    def propertyChanged(newVal: T): PropertyChanged[T]
  }

  case class ChangeProperty[T](prop: Propagatable[T], newVal: T)
  case class PropertyChanged[T](prop: Propagatable[T], newVal: T)

  object NumberLike {
    implicit object PropagatableInt extends Propagatable[Int] {

      def changeProperty(newVal: Int): ChangeProperty[Int] = ChangeProperty(this, newVal)
      def propertyChanged(newVal: Int): PropertyChanged[Int] = PropertyChanged(this, newVal)
    }
  }
}

/*
case statement (identify message) extract variables
  call method with variables

e.g.

case SetDeafenUser(uuid, setDeafen) =>
  setDeafen(uuid, setDeafen)
*/


object BigBoss {
  def props(settings: Settings, record: Boolean, settingsFilename: Option[String]): Props = Props(classOf[BigBoss], settings, record, settingsFilename)
  //connection commands
  case class Connect(remoteAddress: InetSocketAddress)
  case class Disconnect(remoteAddress: InetSocketAddress)
  case object DisconnectAll

  //Peer creation messages
  case class IncomingConnection(remoteAddress: InetSocketAddress, localAddress: InetSocketAddress, socketRef: ActorRef)
  case class PeerShook(remoteAddress: InetSocketAddress, remoteUUID: UUID, peerRef: ActorRef, remotePexAddress: InetSocketAddress)


  //Everything below is mostly UI messaging

  //chat commands
  case class Shout(msg: String)
  case class ReceivedMessage(senderId: UUID, msg: String)

  /* StartSpeaking & StopSpeaking change whether audio data is recorded and sent through pipeline.
   * Mute & UnMute disable & enable, respectively, StartSpeaking */
  case object StartSpeaking
  case object StopSpeaking

  /* TODO: is there a way to not define a case class for every user state? */
  /* TODO: Could implement some kind of database + DSL */
  case class SetNickname(uuid: UUID, newName: String)
  case class SetMuteUser(uuid: UUID, setMute: Boolean)
  case class SetDeafenUser(uuid: UUID, setDeafen: Boolean)
  case class SetBlockUser(uuid: UUID, setBlock: Boolean)
  case class SetServerMuteUser(uuid: UUID, setMute: Boolean)
  case class SetServerDeafenUser(uuid: UUID, setDeafen: Boolean)
  case class SetVolumeUser(uuid: UUID, vol: Double)
  case class SetPTTDelay(delay: FiniteDuration)
  case class SetPTTShortcut(shortcut: Settings.KeyBinding)
  case object SaveSettings

  case object CloseFile
  case class ReadFile(filename: String)
  case class OpenFile(filename: String)

  /* Send audio from file to peers */
  case class PlayAudioFile(filename: String, filetype: String)
  /* Record from microphone */
  case class RecordAudioFile(filename: String, filetype: String)

  case object GetSupportedMixers
  case class SetInputMixer(mixer: Mixer.Info)
  case class SetOutputMixer(mixer: Mixer.Info)

  case class SetListenAddress(addr: InetSocketAddress)
  case class UpdatePeerPexAddress(uuid: UUID, addr: InetSocketAddress)

  case object GetJoinLink

  case class ShowErrorMessage(msg: String)



  case object DebugPrintConnectedPeers
  //Log Decoded audio
  //case class StartLoggingPeer(addr: InetSocketAddress, filename: String)
  //case class StopLoggingPeer(addr: InetSocketAddress, filename: String)
}

/**
 * Invariants we need to enforce: one Peer per BigBoss-BigBoss pair; BigBoss cannot connect to itself
 *
 * TODO: state transition stuff for peers:
 * when we switch into play mode, all peers need to update, all future peers need to start in correct state
 * vice versa
 *
 * TODO: audio probably playing too fast/slow not sure which, decoding from opus at 48k, playing at 44.1k
 */
class BigBoss(
    settings: Settings,
    record: Boolean,
    settingsFilename: Option[String],
  ) extends Actor with ActorLogging {
  import context._
  import Tcp._

  /* TODO: handle edge case where there are no supported mixers */
  val format = settings.soundSettings.audioFormat.toJavax
  val bufSize = settings.soundSettings.bufferSize
  val sourceInfo = new DataLine.Info(classOf[SourceDataLine], format, bufSize)
  val targetInfo = new DataLine.Info(classOf[TargetDataLine], format, bufSize)
  val sourceMixes = AudioUtils.getSupportedMixers(sourceInfo).map(_.getMixerInfo)
  val targetMixes = AudioUtils.getSupportedMixers(targetInfo).map(_.getMixerInfo)
  var sourceMixer = sourceMixes(0)
  var targetMixer = targetMixes(0)

  //IP address we listen for new connections on
  /* TODO: handle failing to find public ip address */
  /* TODO: handle address from settings */
  /* TODO: settings localAddress is a preference, alert user if that address is no longera available */
  var listenAddress = new InetSocketAddress(Tracker.findPublicIp().get, settings.localPort)
  var connHandler = newConnHandler(listenAddress)
  def newConnHandler(listenAddress: InetSocketAddress): ActorRef = {
    /*
    val toRemove = "ilr".toSet
    val words = sentence.filterNot(toRemove)
    */
    val name = s"hc-connection-handler-${listenAddress.toString.drop(1)}"
    context.actorOf(ConnectionHandler.props(listenAddress), name)
  }

  /*
  val localAddrs = Tracker.allLocalAddresses
  log.debug(s"TESTIP: ${Tracker.findPublicIp().get} :: ${localAddrs.mkString(" :: ")}")
  localAddrs.foreach { addr =>
    log.debug(s"addr $addr, ${Tracker.encodeIpToUrl(new InetSocketAddress(addr, 41330)).get} :: ${addr.getCanonicalHostName}, ${addr.getHostAddress}, ${addr.getHostName}, ${addr.isAnyLocalAddress}, ${addr.isLinkLocalAddress}, ${addr.isLoopbackAddress}, ${addr.isMCGlobal}, ${addr.isMCLinkLocal}, ${addr.isMCNodeLocal}, ${addr.isMCOrgLocal}, ${addr.isMCSiteLocal}, ${addr.isMulticastAddress}, ${addr.isSiteLocalAddress}")
  }
  */

  /* TODO: support multiple simultaneous file reads/writes */
  val filereader = context.actorOf(FileReader.props(), "hc-filereader")
  val filewriter = context.actorOf(FileWriter.props(), "hc-filewriter")

  val decoder = context.actorOf(Decoder.props(Sf48000, 1), "hc-decoder")
  val encoder = context.actorOf(Encoder.props(20, Sf48000, 1), "hc-encoder")


  /* Table of active peers */
  val peerTable = new PeerTable(self)

  var localPeerState = settings.userSettings
  parent ! ToView(PeerState.NewPeer(localPeerState))


  def updateUserSettings(newPeerState: Peer): Unit = {
    localPeerState = newPeerState
    settings.userSettings = localPeerState
    parent ! ToView(PeerState.UpdatePeer(newPeerState))
  }

  def updatePeerSettings(newPeerState: Peer): Unit = {
    settings.peerSettings(newPeerState.id) = newPeerState
  }

  override def postStop {
    log.debug("write settings due to postStop")
    settings.writeSettingsFile(settingsFilename)
  }

  def removePreShakePeer(peerTuple: PeerTable.PreShakePeer): Unit = {
    peerTuple._2 ! PeerActor.Disconnect
    peerTable.shakingPeers -= peerTuple
  }
  def removePostShakePeer(peerTuple: PeerTable.PostShakePeer): Unit = {
    peerTuple._2 ! PeerActor.Disconnect
    encoder ! RemoveSubscriber(peerTuple._2)
    peerTable.shookPeers -= peerTuple
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
      startRecord()
    }
    recI += 1
  }

  def startRecord(): Unit = {
    log.debug(s"starting record: $sender")
    recorder.foreach(_ ! Recorder.Record)
    updateUserSettings(localPeerState.copy(speaking = true))
  }

  def stopRecord(): Unit = {
    recorder.foreach(
      system.scheduler.scheduleOnce(settings.pttDelayInMilliseconds, _, Recorder.Pause)
    )
    log.debug(s"pausing record: $sender")
    updateUserSettings(localPeerState.copy(speaking = false))
  }


  /* Only send Pex messages to the newly connected peer
   */
  def sendPexMessage(newPeer: ActorRef): Unit = {
    val pexAddresses = peerTable.shookPeers.map(x => (x._6.getAddress.asInstanceOf[Inet6Address], x._6.getPort))
    log.debug(s"sending PEX to peer ($newPeer): ${pexAddresses}")
    newPeer ! HcPex6Message(pexAddresses.toVector)
  }

  def completeHandshake(remoteAddr: InetSocketAddress, peerRef: ActorRef, remoteUUID: UUID, pexAddr: InetSocketAddress): Unit = {
    log.debug(s"Completing Handshake for: $remoteAddr, $remoteUUID, $peerRef, $pexAddr")
    peerRef ! PeerActor.HandshakeComplete(settings.peerSettings.get(remoteUUID))
    peerRef ! PeerActor.SetMixer(sourceInfo, sourceMixer)
    context watch peerRef
    peerTable.completeShake(remoteAddr, peerRef, remoteUUID, pexAddr)
    encoder ! AddSubscriber(peerRef)
    sendPexMessage(peerRef)
  }

  def receive: Receive = {
    //Outgoing Connection
    case BigBoss.Connect(remoteAddress) =>
      log.debug(s"BigBoss.Connect checking for valid address: $remoteAddress")
      if (peerTable.preShakeVerify(remoteAddress, listenAddress)) {
        log.debug(s"send connection to $remoteAddress")
        val peerRef = context.actorOf(PeerActor.props(remoteAddress, null, PeerActor.HandshakeInitiator, localPeerState, listenAddress), "hc-peer-out-" + genPeerName(remoteAddress))
        peerTable.shakingPeers += ((remoteAddress, peerRef, true, Instant.now))
      } else {
        /* TODO: don't like this, consider two routes, one for UI, one for pex + other connects*/
        if (sender == parent) {
          //UI initiated connect
          /* TODO: differentiate between diff types of invalid */
          self ! BigBoss.ShowErrorMessage("Invalid Connection Address")
        }
        log.debug(s"$remoteAddress is an invalid connection address")
      }

    //Incoming Connection, message received from connectionHandler
    case BigBoss.IncomingConnection(remoteAddress, localAddress, socketRef) =>
      log.debug(s"checking incoming peer: $remoteAddress -> $localAddress")
      //check that this peer isn't ourselves, and that we aren't already connected to it
      if (!peerTable.preShakeVerify(remoteAddress, listenAddress)) {
        log.debug(s"Duplicate/Self-connected peer incoming: $remoteAddress -> $localAddress")
        socketRef ! Write(ByteString(Codec[HcMessage].encode(HcShakeDisconnectMessage).require.toByteArray))
      } else {
        log.debug(s"Creating Peer actor: $remoteAddress -> $localAddress")
        val peerRef = context.actorOf(PeerActor.props(remoteAddress, socketRef, PeerActor.HandshakeReceiver, localPeerState, listenAddress), "hc-peer-in-" + genPeerName(remoteAddress))
        socketRef ! Register(peerRef)
        peerTable.shakingPeers += ((remoteAddress, peerRef, false, Instant.now))
      }

    /* TODO: what if we attempt two handshakes to same peer */
    case BigBoss.PeerShook(remoteAddress, remoteUUID, peerRef, remotePexAddress) =>
      log.debug(s"Received shook notice from: $peerRef: $remoteAddress, $remoteUUID, $remotePexAddress")
      if (peerTable.postShakeVerify(remoteAddress, remotePexAddress)) {
        completeHandshake(remoteAddress, peerRef, remoteUUID, remotePexAddress)
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
        log.debug(s"peer conflict: $remoteAddress, $remotePexAddress, ${peerTable.shakingPeers}, ${peerTable.shookPeers}")
        val newPeer = peerTable.getUnShookByPeerRef(peerRef).get
        val oldPeer = (peerTable.getShookByAddr(remoteAddress) orElse peerTable.getShookByPexAddr(remotePexAddress)).get
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
            if (localPeerState.id.compareTo(remoteUUID) < 0) {
              log.debug(s"pconf 3: ${oldPeer}, ${newPeer}")
              removePostShakePeer(oldPeer)
              completeHandshake(remoteAddress, peerRef, remoteUUID, remotePexAddress)
            } else {
              log.debug(s"pconf 4: ${oldPeer}, ${newPeer}")
              removePreShakePeer(newPeer)
            }
          } else if (oldPeer._3) {
            if (localPeerState.id.compareTo(remoteUUID) < 0) {
              log.debug(s"pconf 5: ${oldPeer}, ${newPeer}")
              removePreShakePeer(newPeer)
            } else {
              log.debug(s"pconf 6: ${oldPeer}, ${newPeer}")
              removePostShakePeer(oldPeer)
              completeHandshake(remoteAddress, peerRef, remoteUUID, remotePexAddress)
            }
          }
        }
      }
      log.debug(s"peer shook OWOR: $peerRef, ${peerTable.shakingPeers}, ${peerTable.shookPeers}")

    /* TODO: are there race conditions here, we don't disconnect from some very young peers
     * another race condition - peer completes handshake in between loop1 and loop2
     */
    case BigBoss.DisconnectAll =>
      peerTable.shakingPeers.foreach(removePreShakePeer(_))
      peerTable.shookPeers.foreach(removePostShakePeer(_))
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

    /* Write to settings file, so that Peer settings are correct if/when peer reconnects
     * Forward RemovePeer messages to controller. */
    case msg: PeerState.RemovePeer =>
      log.debug(s"write settings due to remove Peer: $msg")
      settings.writeSettingsFile(settingsFilename)
      parent ! ToView(msg)
    /* Update Settings file with new peers and updates to peers  */
    case msg: PeerState.PeerStateChange =>
      updatePeerSettings(msg.peer)
      parent ! ToView(msg)

    //public chat commands
    case BigBoss.Shout(msg) =>
      log.debug(s"Sending msgs to: ${peerTable.shookPeers}")
      peerTable.shookPeers.foreach(peer => peer._2 ! HcTextMessage(Instant.now, msg))
      self ! BigBoss.ReceivedMessage(localPeerState.id, msg)

    //Received a chat message
    case msg: BigBoss.ReceivedMessage => parent ! ToView(msg)

    case BigBoss.StartSpeaking if !localPeerState.muted =>
      /* TODO: this is conditional based on logging */
      /* TODO: filename based on name */
      //filewriter ! FileWriter.OpenWav("test_data/output_test.wav", pcmFmt)
      startRecord()

    case BigBoss.StopSpeaking if !localPeerState.muted =>
      stopRecord()

    /* for now, only support changing local user's nickname */
    /* TODO: update all peers with new nickname */
    case BigBoss.SetNickname(uuid, newName) =>
      if (uuid == localPeerState.id) {
        log.debug(s"Changing $uuid nickname to $newName")
        updateUserSettings(localPeerState.copy(nickname = newName))
        peerTable.shookPeers.foreach(_._2 ! HcChangeNicknameMessage(newName))
      }
    case BigBoss.SetMuteUser(uuid, setMute) =>
      peerTable.getShookByUUID(uuid) match {
        case Some(remotePeerState) =>
          log.debug(s"Setting mute on user: $uuid to $setMute")
          if (setMute) {
            remotePeerState._2 ! PeerActor.MuteAudio
          } else {
            remotePeerState._2 ! PeerActor.PlayAudio
          }
        case None =>
          log.debug(s"Setting mute on self to $setMute")
          updateUserSettings(localPeerState.copy(muted = setMute))
      }
    case BigBoss.SetDeafenUser(uuid, setDeafen) =>
      if (uuid == localPeerState.id) {
        if (setDeafen) {
          peerTable.shookPeers.foreach(_._2 ! PeerActor.MuteAudio)
        } else {
          peerTable.shookPeers.foreach(_._2 ! PeerActor.PlayAudio)
        }
        updateUserSettings(localPeerState.copy(deafened = setDeafen))
      }

    case BigBoss.SetBlockUser(user, setBlock) =>
      log.debug(s"Unimplemented: Blocking User: $user, $setBlock")
    case BigBoss.SetServerMuteUser(user, setMute) =>
      log.debug(s"Unimplemented: Server Muting User: $user, $setMute")
    case BigBoss.SetServerDeafenUser(user, setDeafen) =>
      log.debug(s"Unimplemented: Server Deafening User: $user, $setDeafen")

    /* Volume control */
    case BigBoss.SetVolumeUser(uuid, volume) =>
      peerTable.getShookByUUID(uuid).foreach(_._2 ! AudioControl.SetVolume(volume))

    /* PTT Settings */
    case BigBoss.SetPTTDelay(delay) =>
      settings.pttDelayInMilliseconds = delay
    case BigBoss.SetPTTShortcut(shortcut) =>
      settings.updateShortcut("ptt", shortcut)

    /* Mixer Options */
    case BigBoss.GetSupportedMixers =>
      val sourceMixers = AudioUtils.getSupportedMixers(sourceInfo).map(_.getMixerInfo)
      val targetMixers = AudioUtils.getSupportedMixers(targetInfo).map(_.getMixerInfo)

      log.debug(s"Sending Source Mixers: ${sourceMixers.mkString(" :: ")}")
      log.debug(s"Sending Target Mixers: ${targetMixers.mkString(" :: ")}")

      parent ! ToView(HcView.OutputMixers(sourceMixer, sourceMixers))
      parent ! ToView(HcView.InputMixers(targetMixer, targetMixers))
      parent ! ToView(HcView.LocalAddresses(listenAddress, Tracker.allLocalAddresses))
    case BigBoss.SetInputMixer(mixer) =>
      /* TODO: make sure that if we change mixer in bigboss, it is updated in the GUI */
      log.debug(s"new input mixer recved: $mixer, $targetMixer")
      if (mixer != targetMixer) {
        targetMixer = mixer
        settings.soundSettings = settings.soundSettings.copy(inputMixer = mixer)
        respawnRecorder()
      }
    case BigBoss.SetOutputMixer(mixer) =>
      log.debug(s"new output mixer recved: $mixer")
      if (mixer != sourceMixer) {
        sourceMixer = mixer
        settings.soundSettings = settings.soundSettings.copy(outputMixer = mixer)
        peerTable.shookPeers.foreach( _._2 ! PeerActor.SetMixer(sourceInfo, sourceMixer) )
      }

    /** kill connectionHandler (handle pending connections first)
     *  Notify peers that our ListenAddress has changed (for PEX purposes)
     *  modify listenAddress on settings
     * TODO: test preshake peers getting the message
     */
    case BigBoss.SetListenAddress(addr) =>
      log.debug(s"got SetListenAddress message $addr")
      if (addr == listenAddress) {
        log.debug(s"'new Listen Address' $addr is the same as current: $listenAddress")
      } else {
        connHandler ! PoisonPill
        listenAddress = addr
        /* BUG: if user changes address really quickly, can crash BigBoss with an InvalidActorNameException */
        connHandler = newConnHandler(listenAddress)
        peerTable.shakingPeers.foreach( _._2 ! PeerActor.SetListenAddress(addr) )
        peerTable.shookPeers.foreach( _._2 ! PeerActor.SetListenAddress(addr) )
      }

    case BigBoss.UpdatePeerPexAddress(uuid, addr) =>
      log.debug(s"updating peer table pex address $uuid, $addr")
      peerTable.updatePexAddress(uuid, addr)


    case BigBoss.SaveSettings =>
      log.debug("write settings due to SaveSettings")
      settings.writeSettingsFile(settingsFilename)

    case BigBoss.GetJoinLink =>
      Tracker.findPublicIp match {
        case Some(addr) =>
          val sockAddr = new InetSocketAddress(addr, settings.localPort)
          parent ! ToView(HcView.JoinLink(Tracker.encodeIpToUrl(sockAddr).get))
        case None => ()
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
        filereader ! FileReader.OpenWav(filename, settings.soundSettings.audioFormat)
      } else {
        log.debug(s"Unsupported Filetype: $filename, $filetype")
      }

    case BigBoss.RecordAudioFile(filename, filetype) =>
      ()

    case BigBoss.ShowErrorMessage(msg) =>
      /* TODO: replace with more flexible notification/error system, with variable styling
       * e.g. Noise + Toast, Toast only, Flash shit, ...etc
       */
      import herochat.SnakeController.ToView
      import herochat.HcView
      import herochat.ui.Toast
      parent ! ToView(HcView.ShowToast(msg, Toast.Error))

    case BigBoss.DebugPrintConnectedPeers =>
      log.debug(s"Connected Peers: ${peerTable.shookPeers.map(_._6)}")

    case _ @ msg => log.debug(s"Bad Msg: $msg")
  }
}
