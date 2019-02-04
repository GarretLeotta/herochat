package herochat

import scala.language.postfixOps

import scala.collection.JavaConverters._
import scala.collection.mutable.Buffer
import scala.concurrent.duration._
import scala.util.Try

import akka.actor.{ActorRef, Props, Actor, PoisonPill, ActorLogging}

import scalafx.Includes._
import scalafx.application.Platform
import scalafx.collections.{ObservableBuffer}
import scalafx.embed.swing.SFXPanel
import scalafx.event.EventHandler
import scalafx.scene.input.{KeyEvent, KeyCode}

import java.net.{InetAddress, InetSocketAddress}

import javax.sound.sampled.{Mixer}

import herochat.actors.{BigBoss}
import herochat.SnakeController.ToModel

object HcView {
  def props(localUser: User): Props = Props(classOf[HcView], localUser)

  abstract class HcViewMessage

  case object GuiInitialized

  case object ShowDefault extends HcViewMessage
  case object ShowOptions extends HcViewMessage

  case class ConnectString(input: String) extends HcViewMessage
  case object DisconnectFromLobby extends HcViewMessage

  case class SendMessage(msg: String)

  /* AddPeer and updateState are currently the same */
  case class AddPeer(peerState: Peer) extends HcViewMessage
  case class UpdatePeerState(newState: Peer) extends HcViewMessage
  case class RemovePeer(user: User) extends HcViewMessage

  case class InputMixers(currentMixer: Mixer.Info, mixers: Array[Mixer.Info])
  case class OutputMixers(currentMixer: Mixer.Info, mixers: Array[Mixer.Info])
}

/**
 * Propagate changes between model and view
 * TODO: determine when I need to run code in Platform.runLater
 */
class HcView(localUser: User) extends Actor with ActorLogging {
  import context._

  //start JFXApp, explicitly pass self
  val guiInstance = new HcGUI(localUser)(self)
  val guiThread = new Thread(new Runnable() {
    override def run(): Unit = {
      guiInstance.main(Array[String]())
      //when thread ends, ShutDown actor system
      log.debug(s"GUI thread ended, shutting down Actor System")
      parent ! ShutDown
    }
  }).start()



  //stop javafx when this actor stops
  override def postStop {
    log.debug(s"Stopping $self, ending GUI thread")
    Platform.exit()
    Platform.runLater {
      log.debug(s"We shouldn't see this")
    }
  }

  def parsePort(inport: String): Option[Int] = {
    Try(inport.toInt).toOption
  }

  val ip4_pattern = "([0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}):?([0-9]{0,5})"
  val ip6p_pattern = "\\[([0-9a-zA-Z:]*)\\]:([0-9]{0,5})"
  val ip6_pattern = "([0-9a-zA-Z:]+)"
  val url_pattern = "herochat\\.net/([0-9a-zA-Z-_]+)"

  /* defer all messages until GUI thread is initialized */
  var pre_init_msgs = Buffer[Tuple2[ActorRef, Any]]()
  def receive: Receive = {
    case HcView.GuiInitialized =>
      become(init_receive)
      pre_init_msgs.foreach{case (orig_sender, msg) => self.tell(msg, orig_sender)}
    case x => pre_init_msgs += ((sender, x))
  }

  def init_receive: Receive = {
    //Events from UI
    case HcView.ShowDefault =>
      guiInstance.showDefault()
    case HcView.ShowOptions =>
      guiInstance.showOptions()
      parent ! ToModel(BigBoss.GetSupportedMixers)
    case HcView.ConnectString(input: String) =>
      input match {
        //validation in View Actor
        case ip4_pattern.r(addr, port) =>
          /* TODO: handle no port*/
          log.debug(s"match_ip4: $addr, $port.")
          Try(port.toInt).toOption.foreach((x: Int) => parent ! BigBoss.Connect(new InetSocketAddress(addr, x)))
        case ip6p_pattern.r(addr, port) =>
          log.debug(s"match_ip6p: $addr,$port.")
          Try(port.toInt).toOption.foreach((x: Int) => parent ! BigBoss.Connect(new InetSocketAddress(addr, x)))
        case ip6_pattern.r(addr) =>
          log.debug(s"match_ip6: $addr.")
          /* TODO: no port */
        case url_pattern.r(encodedIp) =>
          log.debug(s"match_url: $encodedIp, ${Tracker.decode_ip_from_url(encodedIp)}")
          Tracker.decode_ip_from_url(encodedIp).foreach(parent ! BigBoss.Connect(_))
        case _ => log.debug("Got some bullshit")
      }
    case HcView.DisconnectFromLobby =>
      parent ! BigBoss.DisconnectAll
    case HcView.SendMessage(msg) =>
      //TODO: send modes other than shout
      parent ! BigBoss.Shout(msg)

    //Events from Controller
    case PeerState.NewPeer(peerState) => Platform.runLater {
      guiInstance.userMap += ((peerState.user, peerState))
      //println(s"view added peer $peerState, ${guiInstance.userMap}")
    }
    case PeerState.UpdatePeer(peerState) => Platform.runLater {
      guiInstance.userMap.update(peerState.user, peerState)
      //println(s"view updated peer $peerState, ${guiInstance.userMap}")
    }
    case PeerState.RemovePeer(peerState) => Platform.runLater {
      guiInstance.userMap -= peerState.user
      //println(s"view removed peer $peerState, ${guiInstance.userMap}")
    }

    case HcView.InputMixers(currentMixer, mixers) =>
      log.debug(s"got input ${mixers.mkString(" :: ")}")
      guiInstance.updateOptionsInputMixers(currentMixer, mixers)
      //guiInstance.inMixers.clear()
      //guiInstance.inMixers ++= mixers
      //guiInstance.selectedInMixer = currentMixer
    case HcView.OutputMixers(currentMixer, mixers) =>
      log.debug(s"got output ${mixers.mkString(" :: ")}")
      guiInstance.updateOptionsOutputMixers(currentMixer, mixers)
      //guiInstance.outMixers.clear()
      //guiInstance.outMixers ++= mixers
      //guiInstance.selectedOutMixer = currentMixer

    case ToModel(msg) => parent ! ToModel(msg)

    case msg: ChatMessage => Platform.runLater {
      guiInstance.messages += msg
      log.debug(s"received chat message: $msg")
    }
    case _ @ msg => log.debug(s"Bad Msg: $msg, $sender")
  }
}
