package herochat


import akka.actor.{ActorRef, Props, Actor, ActorLogging}

import scalafx.embed.swing.SFXPanel

import actors.{BigBoss}

object SnakeController {
  def props(killswitch: ActorRef, recordAudio: Boolean, settingsFilename: Option[String]): Props = Props(classOf[SnakeController], killswitch, recordAudio, settingsFilename)

  case class ToView(msg: Any)
  case class ToModel(msg: Any)
}

/**
 * Propogate changes between model and view
 * TODO: listenPort, user should be mutable
 */
class SnakeController(
    killswitch: ActorRef,
    recordAudio: Boolean,
    settingsFilename: Option[String],
  ) extends Actor with ActorLogging {
  import context._
  import SnakeController._

  new SFXPanel() // trick: create empty panel to initialize toolkit

  val settings = herochat.Settings.readSettingsFile(settingsFilename)
  //log.debug(s"settings from json: ${settings.soundSettings}, ${settings.userSettings}, ${settings.peerSettings}")

  val model = context.actorOf(BigBoss.props(settings, recordAudio, settingsFilename), "bigboss")
  val view = context.actorOf(HcView.props(settings), "herochat-view")

  def receive: Receive = {
    /* really, any message from view goes to model and vice versa, can just check sender */
    case ToView(msg) => view ! msg
    case ToModel(msg) => model ! msg

    case ShutDown => killswitch ! ShutDown
    case _ @ msg => log.debug(s"Bad Msg: $msg, $sender")
  }
}

object FakeController {
  def props(killswitch: ActorRef, recordAudio: Boolean, settingsFilename: Option[String]): Props = Props(classOf[FakeController], killswitch, recordAudio, settingsFilename)
}



/**
 * Parent actor that controls UI-less BigBoss instances
 */
class FakeController(
    killswitch: ActorRef,
    recordAudio: Boolean,
    settingsFilename: Option[String]
  ) extends Actor with ActorLogging {
  import context._

  val settings = herochat.Settings.readSettingsFile(settingsFilename)
  //log.debug(s"settings from json: ${settings.soundSettings}, ${settings.userSettings}, ${settings.peerSettings}")

  val model = context.actorOf(BigBoss.props(settings, recordAudio, settingsFilename), "bigboss")

  def receive: Receive = {
    case SnakeController.ToModel(msg) => model ! msg
    case ShutDown => killswitch ! ShutDown
    case _ @ msg => log.debug(s"Bad Msg: $msg, $sender")
  }
}
