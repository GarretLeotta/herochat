package herochat

import scala.language.postfixOps
import scala.collection.JavaConverters._
import scala.concurrent.duration._

import akka.actor.{ActorSystem, ActorRef, Props, Actor, Terminated}
import akka.io.{IO, Tcp, Inet}
import akka.util.{Timeout, ByteString}


import java.io.{BufferedReader, DataOutputStream, InputStreamReader}
import java.net.{InetSocketAddress, Socket, SocketAddress}
import java.nio.{ByteBuffer}
import java.nio.channels.{SelectionKey, Selector, ServerSocketChannel, SocketChannel}




class Server(localAddress: InetSocketAddress) extends Actor {
  import context._
  import Tcp._

  IO(Tcp) ! Tcp.Bind(self, localAddress, options=List(Tcp.SO.ReuseAddress(true)))

  def receive: Receive = {
    case Bound(boundAddress) =>
      println(s"$self: bound to: $boundAddress, ${boundAddress == localAddress}")
    case Connected(rAddr, lAddr) =>
      println(s"$self: received connection from: $rAddr, $lAddr")
    case msg => println(s"$self: Bad: $msg, $sender")
  }
}

object Client {
  case class Connect(remoteAddress: InetSocketAddress, localAddress: Option[InetSocketAddress])
}
class Client() extends Actor {
  import context._
  import Tcp._


  def receive = {
    case Client.Connect(remoteAddress, localAddress) =>
      println(s"$self: attempting to connect to : $remoteAddress, $localAddress")
      IO(Tcp) ! Connect(remoteAddress, localAddress, options=List(Tcp.SO.ReuseAddress(true)))
    case Connected(remoteAddress, localAddress) =>
      println(s"$self: connected to: $remoteAddress, $localAddress")
    case msg => println(s"$self: Bad: $msg, $sender")
  }
}


object ConnectTest extends App {
  val system = ActorSystem("connection_test")
  val killswitch = system.actorOf(Props(classOf[Killswitch]),  "killswitch")
  import system.dispatcher

  val servAddr1 = new InetSocketAddress("::1", 1337)
  val servAddr2 = new InetSocketAddress("::1", 7331)

  val testAddr1 = new InetSocketAddress("::1", 3333)
  val testAddr2 = new InetSocketAddress("::1", 4444)
  val testAddr3 = new InetSocketAddress("::1", 5555)

  //val server = system.actorOf(Props(classOf[Server], servAddr1), "server")
  val revres = system.actorOf(Props(classOf[Server], servAddr2), "revres")


  val client1 = system.actorOf(Props(classOf[Client]), "client-1")
  val client2 = system.actorOf(Props(classOf[Client]), "client-2")
  val client3 = system.actorOf(Props(classOf[Client]), "client-3")
  val client4 = system.actorOf(Props(classOf[Client]), "client-4")
  val client5 = system.actorOf(Props(classOf[Client]), "client-5")
  val client6 = system.actorOf(Props(classOf[Client]), "client-6")

  def scheduleBulkTasks(delay: FiniteDuration, actor: ActorRef, message: Any) = {
    system.scheduler.scheduleOnce(delay, actor, message)
  }
  type CommandVector = Vector[(FiniteDuration, ActorRef, Any)]
  Vector(
    (1.0 seconds, client1, Client.Connect(servAddr1, Some(servAddr2))),
    (2.0 seconds, client2, Client.Connect(servAddr1, None)),

    //(2.0 seconds, client2, Client.Connect(servAddr2, Some(testAddr2))),
    //(1.0 seconds, client3, Client.Connect(testAddr1, Some(testAddr1))),
    //(1.0 seconds, client5, Client.Connect(servAddr2, None)),
    //(1.0 seconds, client6, Client.Connect(testAddr1, None)),
    (4.0 seconds, killswitch, ShutDown),
  ) map {x => scheduleBulkTasks _ tupled x}
}


/*
object ClientPool {
  case class Connect(remoteAddress: InetSocketAddress, localAddress: Option[InetSocketAddress])
  case class Message(remoteAddress: InetSocketAddress, message: String)
}
class ClientPool() extends Actor {
  import context._

  var clients = scala.collection.mutable.Map[InetSocketAddress, ActorRef]()

  def receive: Receive = {
    case ClientPool.Connect(remoteAddress, localAddress) =>
      clients(remoteAddress) = context.actorOf(Props(classOf[Client], remoteAddress, localAddress), s"client-$remoteAddress".replace("/", ""))
    case ClientPool.Message(remoteAddress, message) =>
      clients(remoteAddress) ! Client.Message(message)
  }

  override def postStop {
    clients.values.foreach(client => context stop client)
  }
}*/



/*
object Server {
  case object Next
}
class Server(localAddress: InetSocketAddress) extends Actor {
  val selector = Selector.open()
  //var dataMapper = scala.collection.mutable.Map[SocketChannel, List]()

  val serverChannel = ServerSocketChannel.open()
  serverChannel.configureBlocking(false)
  serverChannel.socket.bind(localAddress)
  serverChannel.register(selector, SelectionKey.OP_ACCEPT)
  //self ! Server.Next

  def accept(key: SelectionKey): Unit = {
    val serverChannel: ServerSocketChannel = key.channel.asInstanceOf[ServerSocketChannel]
    val channel: SocketChannel = serverChannel.accept
    channel.configureBlocking(false)
    val socket: Socket = channel.socket()
    val remoteAddr: SocketAddress = socket.getRemoteSocketAddress()
    println(s"$self: Connected to: $remoteAddr")

    //dataMapper.put(channel, new ArrayList())
    channel.register(this.selector, SelectionKey.OP_READ)
  }

  //read from the socket channel
  def read(key: SelectionKey): Unit = {
    val channel: SocketChannel = key.channel.asInstanceOf[SocketChannel]
    val buffer: ByteBuffer = ByteBuffer.allocate(1024)
    var numRead: Int = channel.read(buffer)

    if (numRead > 0) {
      val data: Array[Byte] = buffer.array
      println(s"Got message: ${data.map(_.toChar).mkString}")
    } else {
      //end condition
      //this.dataMapper.remove(channel)
      val socket: Socket = channel.socket()
      val remoteAddr: SocketAddress = socket.getRemoteSocketAddress()
      println(s"Connection closed by client: $remoteAddr")
      channel.close()
      key.cancel()
    }
  }

  def receive: Receive = {
    case Server.Next =>
      selector.select()
      val keys = selector.selectedKeys.asScala
      keys.foreach(key => {
        selector.selectedKeys.remove(key)
        if (key.isValid) {
          if (key.isAcceptable) {
            accept(key)
          } else if (key.isReadable) {
            read(key)
          }
        }
      })
      //println(s"serv: veri ${keys.count(x=>true)}")
    case msg => println(s"$self: Bad: $msg, $sender")
  }

  override def postStop {
  }
}


object Client {
  case class Message(message: String)
}
class Client(remoteAddress: InetSocketAddress, localAddress: Option[InetSocketAddress]) extends Actor {
  println(s"Client connecting to $remoteAddress")

  val client = SocketChannel.open(remoteAddress)

  def receive: Receive = {
    case Client.Message(message) =>
      val buffer = ByteBuffer.wrap(message.getBytes)
      client.write(buffer)
      buffer.clear()
    case msg => println(s"$self: Bad: $msg, $sender")
  }

  override def postStop {
    client.close()
  }
}

object ClientPool {
  case class Connect(remoteAddress: InetSocketAddress, localAddress: Option[InetSocketAddress])
  case class Message(remoteAddress: InetSocketAddress, message: String)
}
class ClientPool() extends Actor {
  import context._

  var clients = scala.collection.mutable.Map[InetSocketAddress, ActorRef]()

  def receive: Receive = {
    case ClientPool.Connect(remoteAddress, localAddress) =>
      clients(remoteAddress) = context.actorOf(Props(classOf[Client], remoteAddress, localAddress), s"client-$remoteAddress".replace("/", ""))
    case ClientPool.Message(remoteAddress, message) =>
      clients(remoteAddress) ! Client.Message(message)
  }

  override def postStop {
    clients.values.foreach(client => context stop client)
  }
}


object ConnectTest extends App {
  val system = ActorSystem("connection_test")
  val killswitch = system.actorOf(Props(classOf[Killswitch]),  "killswitch")
  import system.dispatcher


  val servAddr1 = new InetSocketAddress("::1", 1337)
  val servAddr2 = new InetSocketAddress("::1", 7331)

  val testAddr1 = new InetSocketAddress("::1", 3333)
  val testAddr2 = new InetSocketAddress("::1", 4444)
  val testAddr3 = new InetSocketAddress("::1", 5555)

  val server = system.actorOf(Props(classOf[Server], servAddr1), "server")
  val revres = system.actorOf(Props(classOf[Server], servAddr2), "revres")

  val clientPool = system.actorOf(Props(classOf[ClientPool]), "client-pool")

  def scheduleBulkTasks(delay: FiniteDuration, actor: ActorRef, message: Any) = {
    system.scheduler.scheduleOnce(delay, actor, message)
  }
  type CommandVector = Vector[(FiniteDuration, ActorRef, Any)]
  Vector(
    (1.0 seconds, clientPool, ClientPool.Connect(servAddr1, Some(servAddr2))),
    (2.0 seconds, clientPool, ClientPool.Connect(servAddr2, Some(testAddr2))),
    (3.0 seconds, clientPool, ClientPool.Message(servAddr1, "hello buddy")),
    (3.0 seconds, clientPool, ClientPool.Message(servAddr2, "hello buddy")),
    (3.0 seconds, clientPool, ClientPool.Message(servAddr1, "second message")),

    (3.0 seconds, server, Server.Next),
    (3.5 seconds, server, Server.Next),
    //(3.0 seconds, revres, Server.Next),
    //(3.5 seconds, revres, Server.Next),

    (5.0 seconds, killswitch, ShutDown),
  ) map {x => scheduleBulkTasks _ tupled x}
}
*/



/*

val x = {
  val welcomeSocket = new ServerSocket(localAddress.getPort)

  override def postStop {
    welcomeSocket.close()
  }

  def receive: Receive = {
    case Server.Accept =>
      val connectionSocket = welcomeSocket.accept
      println(s"Received connection from: $connectionSocket")
      val inFromClient = new BufferedReader(new InputStreamReader(connectionSocket.getInputStream))
      val outToClient = new DataOutputStream(connectionSocket.getOutputStream)
      val clientSentence = inFromClient.readLine
      println(s"Received: $clientSentence")
      outToClient.writeBytes(s"${clientSentence.toUpperCase}\n")
    case msg => println(s"$self: Bad: $msg, $sender")
  }
}

object Client {
  case class Connect(remoteAddress: InetSocketAddress, localAddress: Option[InetSocketAddress])
}
class Client() extends Actor {
  def receive = {
    case Client.Connect(remoteAddress, localAddress) =>
      println(s"Sending connection to: $remoteAddress")
      val clientSocket = new Socket(remoteAddress.getAddress, remoteAddress.getPort)
      //val clientSocket = new Socket(remoteAddress.getAddress, remoteAddress.getPort, lAddr, lPort)
      println(s"Connected to: $clientSocket")
      val outToServer = new DataOutputStream(clientSocket.getOutputStream())
      val inFromServer = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))
      outToServer.writeBytes("hello there buddy\n")
      println(s"FROM SERVER: ${inFromServer.readLine}")
      clientSocket.close()
    case msg => println(s"$self: Bad: $msg, $sender")
  }
}
*/


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
