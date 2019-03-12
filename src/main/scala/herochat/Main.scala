package herochat

import scala.language.postfixOps
import scala.concurrent.duration._

import akka.actor.{ActorSystem, Props, Actor, ActorRef, ActorLogging}

import java.net.{InetAddress, InetSocketAddress}
import java.util.UUID

//Initialize javafx platform
import javafx.embed.swing.JFXPanel

import actors.{BigBoss}
import SnakeController.{ToModel, ToView}


/**
 *
 */
object Main extends App {
  val system = ActorSystem("herochat")
  val killswitch = system.actorOf(Props(classOf[Killswitch]),  "killswitch")
  import system.dispatcher

  println(s"test, called with args: ${args.mkString(", ")}")
  /* TODO: configure where settings file is installed */
  val controller = system.actorOf(SnakeController.props(killswitch, true, args.lift(0)), s"hcController")

  val ipAddr = Tracker.find_public_ip
  val sockAddr = new InetSocketAddress(ipAddr.get, 41330)
  println(s"public ip: $ipAddr, $sockAddr, ${Tracker.encode_ip_to_url(sockAddr)}")
  //killswitch ! ShutDown
}
