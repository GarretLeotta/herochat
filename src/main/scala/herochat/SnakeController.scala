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

  log.info(s"Starting up now buddy")
  new SFXPanel() // trick: create empty panel to initialize toolkit
  log.info(s"Past this")
  //sbt compatibility - Need to change class loader for javax to work
  val cl = classOf[javax.sound.sampled.AudioSystem].getClassLoader
  val old_cl: java.lang.ClassLoader = Thread.currentThread.getContextClassLoader
  Thread.currentThread.setContextClassLoader(cl)
  override def postStop {
    log.debug(s"Stopping, resetting thread context class loader")
    Thread.currentThread.setContextClassLoader(old_cl)
  }

  var settings = herochat.Settings.readSettingsFile(settingsFilename)
  log.debug(s"settings from json: ${settings.soundSettings}, ${settings.userSettings}, ${settings.peerSettings}")

  val model = context.actorOf(BigBoss.props(settings, recordAudio), "bigboss")
  val view = context.actorOf(HcView.props(settings.userSettings.user), "herochat-view")

  def receive: Receive = {
    case ToView(msg) => view ! msg
    case ToModel(msg) => model ! msg

    case msg: BigBoss.BigBossMessage => model ! msg
    case msg: HcView.HcViewMessage => view ! msg
    case msg: ChatMessage => view ! msg
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

  //sbt compatibility - Need to change class loader for javax to work
  val cl = classOf[javax.sound.sampled.AudioSystem].getClassLoader
  val old_cl: java.lang.ClassLoader = Thread.currentThread.getContextClassLoader
  Thread.currentThread.setContextClassLoader(cl)
  override def postStop {
    log.debug(s"Stopping, resetting thread context class loader")
    Thread.currentThread.setContextClassLoader(old_cl)
  }

  var settings = herochat.Settings.readSettingsFile(settingsFilename)
  log.debug(s"settings from json: ${settings.soundSettings}, ${settings.userSettings}, ${settings.peerSettings}")

  val model = context.actorOf(BigBoss.props(settings, recordAudio), "bigboss")

  def receive: Receive = {
    case SnakeController.ToModel(msg) => model ! msg
    case msg: BigBoss.BigBossMessage => model ! msg
    case ShutDown => killswitch ! ShutDown
    case _ @ msg => log.debug(s"Bad Msg: $msg, $sender")
  }
}
