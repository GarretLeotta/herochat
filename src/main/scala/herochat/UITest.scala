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


case object ShutDown

class Killswitch extends Actor with ActorLogging {
  import context._
  override def receive: Receive = {
    case ShutDown =>
      log.debug(s"got the shutdown message from $sender")
      context.system.terminate()
  }
}


/**
 *
 */
object MVCAkkaTest extends App {
  val system = ActorSystem("herochat")
  val killswitch = system.actorOf(Props(classOf[Killswitch]),  "killswitch")
  import system.dispatcher

  def scheduleBulkTasks(delay: FiniteDuration, actor: ActorRef, message: Any) = {
    system.scheduler.scheduleOnce(delay, actor, message)
  }

  println("hello in UITest")

  /* TODO: test actor creation function */
  //val controller = system.actorOf(SnakeController.props(41330, User(0, "Garret"), killswitch, false), s"hcController")
  //val bigBossT1 = system.actorOf(BigBoss.props(41331, User(1, "Mememan"), true), "bigbosst1")
  //val bigBossT2 = system.actorOf(BigBoss.props(41332, User(2, "Momomonkey"), false), "bigbosst2")
  //val bigBossT3 = system.actorOf(BigBoss.props(41333, User(3, "Memetic Champion"), false), "bigbosst3")
  //val bigBossT4 = system.actorOf(BigBoss.props(41334, User(4, "Moomoo Missus"), false), "bigbosst4")

  var testFunctions = scala.collection.mutable.Map[String, Array[String] => Unit]()
  testFunctions("doNothing") = (x: Array[String]) => {
    Vector(
      (0.5 seconds, killswitch, ShutDown),
    ) map {x => scheduleBulkTasks _ tupled x}
  }
  testFunctions("holdOpen") = (x: Array[String]) => {
    println(s"${System.getenv("APPDATA")}")
  }

  /* TEST FUNCTIONS */

  /* BigBoss without UI acts as recorder */
  def testHeadless(args: Array[String]): Unit = {
    val controller = system.actorOf(SnakeController.props(killswitch, false, None), s"hcController")
    val bigBossT1 = system.actorOf(FakeController.props(killswitch, false, Some("settings.1.json")), "fakeCtrl1")
    val ip6addr = Tracker.find_public_ip().get
    Vector(
      (2.0 seconds, bigBossT1, ToModel(BigBoss.Connect(new InetSocketAddress(ip6addr, 41330)))),
      //(3.0 seconds, bigBossT1, ToModel(BigBoss.SetMuteUser(User(new UUID(0,1), "Mememan"), false))),
      (3.5 seconds, bigBossT1, ToModel(BigBoss.StartSpeaking)),
    ) map {x => scheduleBulkTasks _ tupled x}
  }
  testFunctions("testHeadless") = testHeadless

  /* BigBoss with UI (SnakeController) acts as recorder */
  def testUI(args: Array[String]): Unit = {
    val controller = system.actorOf(SnakeController.props(killswitch, true, None), s"hcController")
    val bigBossT1 = system.actorOf(FakeController.props(killswitch, false, Some("settings.1.json")), "fakeCtrl1")
    //val bigBossT2 = system.actorOf(BigBoss.props(41332, User(new UUID(0,2), "Momomonkey"), false), "bigbosst2")
    import java.util.UUID
    val testUUID = UUID.fromString("86bda808-561b-42cf-9e63-f4c3b43905ef")
    val ip6addr = Tracker.find_public_ip().get
    Vector(
      //(1.0 seconds, bigBossT1, ToModel(BigBoss.SetNickname(testUser, "Glumbert"))),
      (2.0 seconds, bigBossT1, ToModel(BigBoss.Connect(new InetSocketAddress(ip6addr, 41330)))),
      (2.5 seconds, bigBossT1, ToModel(BigBoss.Shout("Hello"))),
      (3.0 seconds, bigBossT1, ToModel(BigBoss.SetNickname(testUUID, "Glumbology"))),
      //(4.0 seconds, bigBossT1, ToModel(BigBoss.Disconnect(new InetSocketAddress("::1", 41330)))),
      //(5.0 seconds, bigBossT1, ToModel(BigBoss.Connect(new InetSocketAddress("::1", 41330)))),
      (5.5 seconds, bigBossT1, ToModel(BigBoss.Shout("Hello again"))),

    ) map {x => scheduleBulkTasks _ tupled x}
  }
  testFunctions("testUI") = testUI


  /* BigBoss with UI (SnakeController) acts as recorder, bunch of FakeControllers connect */
  def testABunch(args: Array[String]): Unit = {
    def createFakeController(i: Int): ActorRef = {
      system.actorOf(FakeController.props(killswitch, false, Some(s"settings.$i.json")), s"fakeCtrl$i")
    }
    def intToSeconds(i: Int): FiniteDuration = {
      (1.0 seconds) + (i * 0.5 seconds)
    }

    val ip6addr = Tracker.find_public_ip().get
    val controller = system.actorOf(SnakeController.props(killswitch, true, None), s"hcController")
    val bigBosses = (1 to 10).map(createFakeController)

    val commands = bigBosses.zipWithIndex.flatMap { case (ref, i) =>
      Vector(
        (intToSeconds(i), ref, ToModel(BigBoss.Connect(new InetSocketAddress(ip6addr, 41330)))),
        (intToSeconds(i) + (0.25 seconds), ref, ToModel(BigBoss.Shout("Hello"))),
      )
    } ++ bigBosses.map { ref =>
      (7 seconds, ref, ToModel(BigBoss.DebugPrintConnectedPeers))
    } ++ Vector((7 seconds, controller, ToModel(BigBoss.DebugPrintConnectedPeers)))
    commands.map( x => scheduleBulkTasks _ tupled x )
  }
  testFunctions("testABunch") = testABunch



  if (args.isEmpty) {
    testFunctions("holdOpen")(Array())
  }
  else {
    testFunctions(args(0))(args.drop(1))
  }
}
