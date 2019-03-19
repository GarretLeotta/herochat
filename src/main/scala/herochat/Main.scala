package herochat

import akka.actor.{ActorSystem, Props}

//Initialize javafx platform
import javafx.embed.swing.JFXPanel

object Main extends App {
  val system = ActorSystem("herochat")
  val killswitch = system.actorOf(Props(classOf[Killswitch]),  "killswitch")
  import system.dispatcher
  val controller = system.actorOf(SnakeController.props(killswitch, true, args.lift(0)), s"hcController")
}
