package herochat

import scala.language.postfixOps
import scala.concurrent.duration._

import akka.stream.scaladsl.Framing
import akka.actor.{ActorSystem, Props, Actor, ActorRef, ActorLogging}

import java.io.FileInputStream

import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.Future


import javax.net.ssl.{SSLContext, X509TrustManager, KeyManager, TrustManagerFactory, KeyManagerFactory}
import java.security.{KeyStore, SecureRandom}
import java.security.cert.X509Certificate

import akka.NotUsed
import akka.stream.{TLSClientAuth}
import akka.stream.TLSProtocol.NegotiateNewSession
import akka.stream.scaladsl.{BidiFlow, Flow, Sink, Source, TLS, Tcp}
import akka.stream.{ActorMaterializer, OverflowStrategy, TLSProtocol, TLSRole}
import akka.util.ByteString


import com.typesafe.sslconfig.akka.AkkaSSLConfig
import com.typesafe.sslconfig.ssl.{ClientAuth}


object TlsClient extends StrictLogging {
  // Flow needed for TLS as well as mapping the TLS engine's flow to ByteStrings
  def tlsClientLayer = {

    // Default SSL context supporting most protocols and ciphers. Embellish this as you need
    // by constructing your own SSLContext and NegotiateNewSession instances.
    val tls = TLS(SSLContext.getDefault, NegotiateNewSession.withDefaults, TLSRole.client)

    // Maps the TLS stream to a ByteString
    val tlsSupport = BidiFlow.fromFlows(
      Flow[ByteString].map(TLSProtocol.SendBytes),
      Flow[TLSProtocol.SslTlsInbound].collect {
        case TLSProtocol.SessionBytes(_, sb) => ByteString.fromByteBuffer(sb.asByteBuffer)
      })

    tlsSupport.atop(tls)
  }

  // Very simple logger
  def logging: BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] = {

    // function that takes a string, prints it with some fixed prefix in front and returns the string again
    def logger(prefix: String) = (chunk: ByteString) => {
      println(prefix + chunk.utf8String)
      chunk
    }

    val inputLogger = logger("> ")
    val outputLogger = logger("< ")

    // create BidiFlow with a separate logger function for each of both streams
    BidiFlow.fromFunctions(outputLogger, inputLogger)
  }

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("sip-client")
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    val source = Source.actorRef(1000, OverflowStrategy.fail)
    val connection = Tcp().outgoingConnection("www.google.com", 443)
    val tlsFlow = tlsClientLayer.join(connection)
    val srcActor = tlsFlow.join(logging).to(Sink.ignore).runWith(source)

    // I show HTTP here but send/receive your protocol over this actor
    // Should respond with a 302 (Found) and a small explanatory HTML message
    srcActor ! ByteString("GET / HTTP/1.1\r\nHost: www.google.com\r\n\r\n")
  }
}



object TLSTest extends App {
  /**
  +---------------------------+               +---------------------------+
  | Flow                      |               | tlsConnectionFlow         |
  |                           |               |                           |
  | +------+        +------+  |               |  +------+        +------+ |
  | | SRC  | ~Out~> |      | ~~> O2   --  I1 ~~> |      |  ~O1~> |      | |
  | |      |        | LOGG |  |               |  | TLS  |        | CONN | |
  | | SINK | <~In~  |      | <~~ I2   --  O2 <~~ |      | <~I2~  |      | |
  | +------+        +------+  |               |  +------+        +------+ |
  +---------------------------+               +---------------------------+
  **/

  import TLSServer._

  implicit val system = ActorSystem("tls_test")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  val killswitch = system.actorOf(Props(classOf[Killswitch]),  "killswitch")
  import system.dispatcher

  system.scheduler.scheduleOnce(1.0 seconds, killswitch, ShutDown)



  // the tcp connection to the server
  val connection = Tcp().outgoingConnection("::1", 42042)

  // ignore the received data for now. There are different actions to implement the Sink.
  val sink = Sink.ignore

  // create a source as an actor reference
  val source = Source.actorRef(1000, OverflowStrategy.fail)

  // join the TLS BidiFlow (see below) with the connection
  val tlsConnectionFlow = tlsStage(TLSRole.client).join(connection)

  // run the source with the TLS conection flow that is joined with a logging step that prints the bytes that are sent and or received from the connection.
  val sourceActor = tlsConnectionFlow.join(logging).to(sink).runWith(source)

  // send a message to the sourceActor that will be send to the Source of the stream
  sourceActor ! ByteString("<message>")

}



object TLSServer extends StrictLogging {
  def tlsStage(role: TLSRole)(implicit system: ActorSystem) = {
    val sslConfig = AkkaSSLConfig.get(system)
    val config = sslConfig.config

    // create a ssl-context that ignores self-signed certificates
    implicit val sslContext: SSLContext = {
        object WideOpenX509TrustManager extends X509TrustManager {
            override def checkClientTrusted(chain: Array[X509Certificate], authType: String) = ()
            override def checkServerTrusted(chain: Array[X509Certificate], authType: String) = ()
            override def getAcceptedIssuers = Array[X509Certificate]()
        }

        val context = SSLContext.getInstance("TLS")
        context.init(Array[KeyManager](), Array(WideOpenX509TrustManager), null)
        context
    }
    // protocols
    val defaultParams = sslContext.getDefaultSSLParameters()
    val defaultProtocols = defaultParams.getProtocols()
    val protocols = sslConfig.configureProtocols(defaultProtocols, config)
    defaultParams.setProtocols(protocols)

    // ciphers
    val defaultCiphers = defaultParams.getCipherSuites()
    val cipherSuites = sslConfig.configureCipherSuites(defaultCiphers, config)
    defaultParams.setCipherSuites(cipherSuites)

    val firstSession = new TLSProtocol.NegotiateNewSession(None, None, None, None)
       .withCipherSuites(cipherSuites: _*)
       .withProtocols(protocols: _*)
       .withParameters(defaultParams)

    val clientAuth = getClientAuth(config.sslParametersConfig.clientAuth)
    clientAuth map { firstSession.withClientAuth(_) }

    val tls = TLS.apply(sslContext, firstSession, role)

    val pf: PartialFunction[TLSProtocol.SslTlsInbound, ByteString] = {
      case TLSProtocol.SessionBytes(_, sb) => ByteString.fromByteBuffer(sb.asByteBuffer)
    }

    val tlsSupport = BidiFlow.fromFlows(
        Flow[ByteString].map(TLSProtocol.SendBytes),
        Flow[TLSProtocol.SslTlsInbound].collect(pf));

    tlsSupport.atop(tls);
  }

  def getClientAuth(auth: ClientAuth) = {
     if (auth.equals(ClientAuth.want)) {
         Some(TLSClientAuth.want)
     } else if (auth.equals(ClientAuth.need)) {
         Some(TLSClientAuth.need)
     } else if (auth.equals(ClientAuth.none)) {
         Some(TLSClientAuth.none)
     } else {
         None
     }
  }

  def logging: BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] = {
    // function that takes a string, prints it with some fixed prefix in front and returns the string again
    def logger(prefix: String) = (chunk: ByteString) => {
      println(prefix + chunk.utf8String)
      chunk
    }

    val inputLogger = logger("> ")
    val outputLogger = logger("< ")

    // create BidiFlow with a separate logger function for each of both streams
    BidiFlow.fromFunctions(outputLogger, inputLogger)
  }
}



/*
bindTls(
    interface: String,
    port: Int,
    sslContext: SSLContext,
    negotiateNewSession: NegotiateNewSession,
    backlog: Int = 100,
    options: Traversable[SocketOption] = Nil,
    idleTimeout: Duration = Duration.Inf
): Source[IncomingConnection, Future[ServerBinding]]
*/

object TLSServerTest extends App{
  implicit val system: ActorSystem = ActorSystem("sip-client")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  import system.dispatcher

  val echo = Flow[ByteString]
    .via(Framing.delimiter(
      ByteString("\n"),
      maximumFrameLength = 256,
      allowTruncation = true))
    .map(_.utf8String)
    .map(_ + "!!!\n")
    .map(ByteString(_))

  TcpServerBindTls("::1", 42042, echo)
  println("Server started")


  val source = Source.actorRef(1000, OverflowStrategy.fail)
  val connection = Tcp().outgoingConnection("::1", 42042)
  val tlsFlow = TlsClient.tlsClientLayer.join(connection)
  val srcActor = tlsFlow.join(TlsClient.logging).to(Sink.ignore).runWith(source)

  srcActor ! ByteString("HELLO MY FRIEND")
  system.scheduler.scheduleOnce(1.0 seconds, srcActor, ByteString("HELLO MY FRIEND 2"))
}
/*
keytool -genkey -keyalg RSA -alias selfsigned -keystore keystore.jks -storepass password -validity 360 -keysize 2048

keytool -export -alias selfsigned -storepass password -file server.cre -keystore
keystore.jks
*/

object TcpServerBindTls extends StrictLogging {
  def apply(hostInterface: String, tcpPort: Int, handler: Flow[ByteString, ByteString, NotUsed])(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    val sslContext = buildSSLContext
    val firstSession = prepareFirstSession(sslContext)
    val connections: Source[Tcp.IncomingConnection, Future[Tcp.ServerBinding]] = Tcp().bindTls(hostInterface, tcpPort, sslContext, firstSession)

    connections runForeach { connection =>
      println(s"New connection: ${connection}")
      connection.handleWith(handler)
    }
  }

  def prepareFirstSession(sslContext: SSLContext)(implicit system: ActorSystem) = {
    val sslConfig = AkkaSSLConfig.get(system);
    val config = sslConfig.config;
    val defaultParams = sslContext.getDefaultSSLParameters();
    val defaultProtocols = defaultParams.getProtocols();
    val defaultCiphers = defaultParams.getCipherSuites();
    val clientAuth = TLSClientAuth.need

    defaultParams.setProtocols(defaultProtocols)
    defaultParams.setCipherSuites(defaultCiphers)

    val firstSession = new TLSProtocol.NegotiateNewSession(None, None, None, None)
       .withCipherSuites(defaultCiphers: _*)
       .withProtocols(defaultProtocols: _*)
       .withParameters(defaultParams)

    firstSession
  }

  def buildSSLContext: SSLContext = {
    val keyStorePassword = "password"
    val keyStoreLocation = "certs/keystore.jks"
    val trustStoreLocation = "certs/keystore.jks"

    /*
    val bufferedSource = io.Source.fromFile(passwordLocation)
    val keyStorePassword = bufferedSource.getLines.mkString
    bufferedSource.close
    */

    val keyStore = KeyStore.getInstance("JKS");
    val keyStoreFIS = new FileInputStream(keyStoreLocation)
    keyStore.load(keyStoreFIS, keyStorePassword.toCharArray())

    val trustStore = KeyStore.getInstance("JKS");
    val trustStoreFIS = new FileInputStream(trustStoreLocation)
    trustStore.load(trustStoreFIS, keyStorePassword.toCharArray())

    val kmf = KeyManagerFactory.getInstance("SunX509")
    kmf.init(keyStore, keyStorePassword.toCharArray())

    val tmf = TrustManagerFactory.getInstance("SunX509")
    tmf.init(trustStore)

    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(kmf.getKeyManagers, tmf.getTrustManagers, new SecureRandom())
    sslContext
  }
}
