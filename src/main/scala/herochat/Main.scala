package herochat

import akka.actor.{ActorSystem, Props}

//Initialize javafx platform
import javafx.embed.swing.JFXPanel

object Main extends App {
  /*
  val system = ActorSystem("herochat")
  val killswitch = system.actorOf(Props(classOf[Killswitch]),  "killswitch")
  import system.dispatcher
  val controller = system.actorOf(SnakeController.props(killswitch, true, args.lift(0)), s"hcController")
  */

  import scodec.{Codec}

  import herochat.GCodecs._
  import herochat.NewHcCodec._
  import herochat.HcCodec
  import java.net.{InetAddress, Inet4Address, Inet6Address}
  import java.time.{Instant}
  import java.util.UUID

  val testUUID = UUID.fromString("86bda808-561b-42cf-9e63-f4c3b43905ef")
  val testInst = Instant.now
  val authMessage = HcAuthMessage(testUUID, 1337, "hello world")
  val textMessage = HcTextMessage(testInst, "Hey guys")

  val textBits = Codec.encode(textMessage).require
  val oldStyleTextMessage = HcCodec.HcMessage(MsgTypeText, textBits.bytes.length.toInt, textBits.bytes)

  val encoded0 = Codec.encode(authMessage).require
  val encoded1 = Codec[HcMessage].encode(authMessage).require
  val encoded3 = textBits
  val encoded4 = Codec[HcMessage].encode(textMessage).require
  val encoded5 = Codec.encode(oldStyleTextMessage).require

  /*
  val ip6addr = Tracker.find_public_ip().get
  val encodeTest = Codec[HcMessage].encode(HcPex4Message(Vector(
    (InetAddress.getByName("localhost").asInstanceOf[Inet4Address], 1337),
    (InetAddress.getByName("localhost").asInstanceOf[Inet4Address], 1337)
  ))).require
  */
  val encodeTest = Codec[HcMessage].encode(HcChangeNicknameMessage("Norbert")).require
  println(s"encode Test: ${encodeTest.bytes}")
  println(s"decode Test: ${Codec[HcMessage].decode(encodeTest).require.value}")

  println(s"testTime: ${testInst}")

  println(s"encoded0: ${encoded0.bytes}")
  println(s"encoded1: ${encoded1.bytes}")
  println(s"encoded3: ${encoded3.bytes}")
  println(s"encoded4: ${encoded4.bytes}")
  println(s"encoded5: ${encoded5.bytes}")

  println(s"decoded1: ${Codec[HcMessage].decode(encoded1).require.value}")
  println(s"decoded4: ${Codec[HcMessage].decode(encoded4).require.value}")
  println(s"decoded5: ${Codec[HcMessage].decode(encoded5).require.value}")
}
