package herochat


import akka.actor.{ActorRef, Props, Actor, ActorLogging}

import scalafx.embed.swing.SFXPanel

import actors.{BigBoss}


object SnakeController {
  def props(port: Int, user: User, killswitch: ActorRef, playAudio: Boolean): Props = Props(classOf[SnakeController], port, user, killswitch, playAudio)

  case class ToView(msg: Any)
  case class ToModel(msg: Any)
}

/**
 * Propogate changes between model and view
 * TODO: listenPort, user should be mutable
 */
class SnakeController(
    listenPort: Int,
    user: User,
    killswitch: ActorRef,
    playAudio: Boolean) extends Actor with ActorLogging {
  import context._
  import SnakeController._

  new SFXPanel() // trick: create empty panel to initialize toolkit

  val model = context.actorOf(BigBoss.props(41330, user, playAudio), "bigboss")
  //there is a problem with this dispatcher, seems to hang the view actor because the Platform is exited
  //val view = context.actorOf(HcView.props().withDispatcher("scalafx-dispatcher"), "herochat-view")
  val view = context.actorOf(HcView.props(user), "herochat-view")

  //debug
  override def postStop {
    log.debug(s"Stopping $self")
  }

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
