package herochat

import scala.language.postfixOps
import scala.concurrent.duration._

import akka.actor.{ActorSystem, Props, Actor, ActorRef}

import java.net.{InetAddress, InetSocketAddress}

//Initialize javafx platform
import javafx.embed.swing.JFXPanel

import actors.{BigBoss}

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
  //create test actors

  val controller = system.actorOf(SnakeController.props(41330, User(0, "Garret"), killswitch, true), s"hcController")
  val bigBossT1 = system.actorOf(BigBoss.props(41331, User(1, "Mememan"), false), "bigbosst1")
  //val bigBossT2 = system.actorOf(BigBoss.props(41332, User(2, "Momomonkey"), false), "bigbosst2")
  //val bigBossT3 = system.actorOf(BigBoss.props(41333, User(3, "Memetic Champion"), false), "bigbosst3")
  //val bigBossT4 = system.actorOf(BigBoss.props(41334, User(4, "Moomoo Missus"), false), "bigbosst4")

  //This test command system is a little messy, but w/e
  type CommandVector = Vector[(FiniteDuration, ActorRef, Any)]
  var testCommands = scala.collection.mutable.Map[String, Array[String] => CommandVector]()
  testCommands("doNothing") = (x: Array[String]) => Vector((0.5 seconds, killswitch, ShutDown))
  testCommands("holdOpen") = (x: Array[String]) => Vector()

  //TODO: explain test
  //UI is speaker, headless boss is listener
  def testChat(args: Array[String]): CommandVector = {
    Vector(
      (2.0 seconds, bigBossT1, BigBoss.Connect(new InetSocketAddress("::1", 41330))),
      //(3.0 seconds, bigBossT1, BigBoss.SetMuteUser(User(0, "Garret"), true)),
      //(3.0 seconds, bigBossT1, BigBoss.DebugPCMFromFile("test_data/output_test.pcm")),
      (3.0 seconds, bigBossT1, BigBoss.PlayAudioFile("test_data/output_test.wav", "wav")),

      //(5.0 seconds, controller, BigBoss.DisconnectAll),
      //(6.0 seconds, bigBossT1, BigBoss.Connect(new InetSocketAddress("::1", 41330))),

      //(3.0 seconds, bigBossT1, BigBoss.DebugReadFile("test_data/output_test.pcm")),

      /*
      (4.0 seconds, bigBossT1, Player.Mute),
      (6.0 seconds, bigBossT1, Player.Mute),
      (8.0 seconds, bigBossT1, Player.Mute),
      (10.0 seconds, bigBossT1, Player.Mute),
      */

      /*
      (3.0 seconds, bigBossT1, BigBoss.Shout("ALLO ALLO ALLO ALLO ALLO ALLO ALLO")),
      (4.0 seconds, bigBossT1, BigBoss.Connect(new InetSocketAddress("::1", 41332))),
      (5.0 seconds, bigBossT1, BigBoss.Shout("They call him BRUCE U")),
      (5.5 seconds, bigBossT2, BigBoss.Shout("Sending in Supa Soldiers")),
      (6.0 seconds, bigBossT3, BigBoss.Connect(new InetSocketAddress("::1", 41332))),
      (6.3 seconds, bigBossT1, BigBoss.Shout("LUL")),
      (6.6 seconds, bigBossT2, BigBoss.Shout("Retards?")),
      (6.9 seconds, bigBossT3, BigBoss.Shout("Supa shark")),
      (7.0 seconds, bigBossT4, BigBoss.Connect(new InetSocketAddress("::1", 41332))),
      (7.2 seconds, bigBossT1, BigBoss.Shout("Hehe")),
      (7.4 seconds, bigBossT2, BigBoss.Shout("How are you today?")),
      (7.6 seconds, bigBossT3, BigBoss.Shout("I prefer men")),
      (7.8 seconds, bigBossT4, BigBoss.Shout("WOW")),
      */

      /*
      (4.0 seconds, controller, HcView.UserMuted(User(1, "Mememan"))),
      (5.0 seconds, controller, HcView.UserDeafened(User(1, "Mememan"))),
      (6.0 seconds, controller, HcView.UserUnmuted(User(1, "Mememan"))),
      (7.0 seconds, controller, HcView.UserUndeafened(User(1, "Mememan"))),
      */

      //(8.0 seconds, bigBossT3, BigBoss.Disconnect(new InetSocketAddress("::1", 41330))),

      /*
      (4.0 seconds, bigBossT1, BigBoss.Undeafen),
      //(8.0 seconds, bigBossT1, BigBoss.DebugLogAudioFrom(User(0, "Garret"), "test_data/output1.pcm")),
      (8.0 seconds, bigBossT2, BigBoss.DebugLogAudioFrom(User(0, "Garret"), "test_data/output2.pcm")),
      (8.0 seconds, bigBossT3, BigBoss.DebugLogAudioFrom(User(0, "Garret"), "test_data/output3.pcm")),
      (8.0 seconds, bigBossT4, BigBoss.DebugLogAudioFrom(User(0, "Garret"), "test_data/output4.pcm")),

      //(16.0 seconds, bigBossT1, BigBoss.DebugStopLogAudioFrom(User(0, "Garret"), "test_data/output1.pcm")),
      (16.0 seconds, bigBossT2, BigBoss.DebugStopLogAudioFrom(User(0, "Garret"), "test_data/output2.pcm")),
      (16.0 seconds, bigBossT3, BigBoss.DebugStopLogAudioFrom(User(0, "Garret"), "test_data/output3.pcm")),
      (16.0 seconds, bigBossT4, BigBoss.DebugStopLogAudioFrom(User(0, "Garret"), "test_data/output4.pcm")),
      */

      //(4.0 seconds, bigBossT1, BigBoss.Connect(Tracker.decode_ip_from_url("fwAAAaFy").get)),
      //(4.0 seconds, bigBossT1, BigBoss.Connect(Tracker.decode_ip_from_url("AAAAAAAAAAAAAAAAAAAAAaFy").get)),
      //(5.0 seconds, bigBossT1, BigBoss.DebugPCMFromFile("test_data/newtest_1_clipped.pcm")),
      //(6.0 seconds, bigBossT1, BigBoss.Shout(ChatMessage(User(0, "TestUser"), "Test message from Big Boss"))),
      //(7.0 seconds, bigBossT1, BigBoss.Shout(ChatMessage(User(0, "TestUser"), "Another one just in case"))),
      //(8.0 seconds, bigBossT1, BigBoss.Shout(ChatMessage(User(0, "TestUser"), "Don't forget this one ;)"))),
    )
  }
  testCommands("testChat") = testChat

  if (args.isEmpty) {
    val schedTasks = testCommands("holdOpen")(Array())
    schedTasks map {x => scheduleBulkTasks _ tupled x}
  }
  else {
    val schedTasks = testCommands(args(0))(args.drop(1))
    schedTasks map {x => scheduleBulkTasks _ tupled x}
  }
}


/*
val addr = Tracker.find_public_ip.get
val encoded = Tracker.encode_ip_to_url(new InetSocketAddress(addr, 1337)).get
val decoded = Tracker.decode_ip_from_url(encoded).get
println(s"$addr")
println(s"${encoded}")
println(s"${decoded}")

val ip4 = "127.0.0.1"
val ip4p = "127.0.0.1:1337"
val ip6 = "2606:a000:c885:6c00:d7c:992f:5244:bd60"
val ip6p = "[2606:a000:c885:6c00:d7c:992f:5244:bd60]:1337"
val url = "herochat.net/JgagAMiFbAANfJkvUkS9YAU5"
val xs = List(ip4, ip4p, ip6, ip6p, url)

//very loose patterns, really just detects whether user input the "idea" of an IP address
val ip4_pattern = "([0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}):?([0-9]{0,5})"
val ip6p_pattern = "\\[([0-9a-zA-Z:]*)\\]:([0-9]{0,5})"
val ip6_pattern = "([0-9a-zA-Z:]+)"
val url_pattern = "herochat\\.net/([0-9a-zA-Z-_]+)"

xs.foreach(x => {
  x match {
    case ip4_pattern.r(addr, port) => println(s"match_ip4: $addr,$port.")
    case ip6p_pattern.r(addr, port) => println(s"match_ip6p: $addr,$port.")
    case ip6_pattern.r(addr) => println(s"match_ip6: $addr.")
    case url_pattern.r(encodedIp) => println(s"match_url: $encodedIp.")
    case _ => println("no match")
  }
})*/
