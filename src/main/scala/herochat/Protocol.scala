package herochat

import scodec.{Codec, CodecTransformation, SizeBound, DecodeResult}
import scodec.bits._
import scodec.codecs._

import java.net.{InetAddress, Inet4Address, Inet6Address}
import java.time.{Instant}
import java.util.UUID


object GCodecs {
  /* Don't really like this, leads to a lot of casting anywhere we encode/decode */
  implicit val inet4Address = Codec[Inet4Address] (
    (addr: Inet4Address) => {
      bytes(4).encode(ByteVector(addr.getAddress))
    },
    (encodedAddr: BitVector) => bytes(4).decode(encodedAddr).map { case x =>
      DecodeResult(InetAddress.getByAddress(x.value.toArray).asInstanceOf[Inet4Address], x.remainder)
    }
  )
  implicit val inet6Address = Codec[Inet6Address] (
    (addr: Inet6Address) => {
      bytes(16).encode(ByteVector(addr.getAddress))
    },
    (encodedAddr: BitVector) => bytes(16).decode(encodedAddr).map { case x =>
      DecodeResult(InetAddress.getByAddress(x.value.toArray).asInstanceOf[Inet6Address], x.remainder)
    }
  )
  implicit val instant = Codec[Instant] (
    (inst: Instant) => {
      (int64 ~~ int64).encode((inst.getEpochSecond, inst.getNano))
    },
    (encodedInst: BitVector) => (int64 ~~ int64).decode(encodedInst).map {
      case DecodeResult((epoch, nano), rem) => DecodeResult(Instant.ofEpochSecond(epoch, nano), rem)
    }
  )
}

/**
 */
object NewHcCodec {
  import GCodecs._

  sealed trait HcMessage

  //case class AuthPayloadWithLobby(uuid: UUID, nickname: String, port: Int, lobbyId: UUID) extends HcPayload
  case class HcAuthMessage(uuid: UUID, port: Int, nickname: String) extends HcMessage
  implicit val hcAuthMessage: Codec[HcAuthMessage] = {
    ("uuid"     | uuid) ::
    ("port"     | uint16) ::
    //max nickname size is 255
    ("nickname" | variableSizeBytes(uint8, utf8)).hlist
  }.as[HcAuthMessage]
  case object HcShakeDisconnectMessage extends HcMessage

  case class HcPex4Message(addresses: Vector[(Inet4Address, Int)]) extends HcMessage
  implicit val hcPex4Message: Codec[HcPex4Message] =
    vector(inet4Address ~~ uint16).complete.as[HcPex4Message]
  case class HcPex6Message(addresses: Vector[(Inet6Address, Int)]) extends HcMessage
  implicit val hcPex6Message: Codec[HcPex6Message] =
    vector(inet6Address ~~ uint16).complete.as[HcPex6Message]

  case class HcChangeNicknameMessage(newName: String) extends HcMessage
  implicit val hcChangeNicknameMessage: Codec[HcChangeNicknameMessage] = utf8.as[HcChangeNicknameMessage]

  case class HcTextMessage(timestamp: Instant, content: String) extends HcMessage
  implicit val hcTextMessage: Codec[HcTextMessage] = {
    ("timestamp" | instant) ::
    ("content"   | utf8).hlist
  }.as[HcTextMessage]


  /* TODO: consistent nomenclature */
  val MsgTypeVersion = 0

  val MsgTypeShakeAuth = 10
  val MsgTypeShakeDisconnect = 11

  val MsgTypePing = 2

  val MsgTypePex4 = 30
  val MsgTypePex6 = 31

  /* TODO: */
  val MsgTypeRequestAudio = 410
  val MsgTypeRefuseAudio = 411
  val MsgTypeRequestPex = 420
  val MsgTypeRefusePex = 421

  val MsgTypeLobbyInfo = 5

  val MsgTypeChangeNickname = 610

  val MsgTypeText = 8

  val MsgTypeAudio = 9


  /* TODO: type and length are Big endian right now, should they be little endian? */
  /* NOTE: is a 16-bit length field enough? 65535 characters. */
  val typeCodec: Codec[Int] = uint16
  val lengthCodec: Codec[Int] = uint16
  implicit val hcPayloadDiscriminated: Discriminated[HcMessage, Int] = Discriminated[HcMessage, Int](typeCodec, new CodecTransformation {
    def apply[X](c: Codec[X]) = variableSizeBytes(lengthCodec, c)
  })
  
  type HcDiscriminator[X] = Discriminator[HcMessage, X, Int]
  implicit val hcAuthDiscriminator: HcDiscriminator[HcAuthMessage] = Discriminator(MsgTypeShakeAuth)
  implicit val hcShakeDisconnectDiscriminator: HcDiscriminator[HcShakeDisconnectMessage.type] = Discriminator(MsgTypeShakeDisconnect)
  implicit val hcPex4Discriminator: HcDiscriminator[HcPex4Message] = Discriminator(MsgTypePex4)
  implicit val hcPex6Discriminator: HcDiscriminator[HcPex6Message] = Discriminator(MsgTypePex6)
  implicit val hcChangeNicknameDiscriminator: HcDiscriminator[HcChangeNicknameMessage] = Discriminator(MsgTypeChangeNickname)
  implicit val hcTextDiscriminator: HcDiscriminator[HcTextMessage] = Discriminator(MsgTypeText)
}


/**
 * TODO: DEPRECATED
 */
object HcCodec {
  //Basic Protocol description

  sealed trait HcPayload
  //case class AuthPayloadWithLobby(uuid: UUID, nickname: String, port: Int, lobbyId: UUID) extends HcPayload
  case class NewAuthPayload(uuid: UUID, port: Int, nickname: String) extends HcPayload
  implicit val newAuthPayload: Codec[NewAuthPayload] = {
    ("uuid"     | uuid) ::
    ("port"     | uint16) ::
    //max nickname size is 255
    ("nickname" | variableSizeBytes(uint8, utf8)).hlist
  }.as[NewAuthPayload]

  case class HcMessage(msgType: Int, msgLength: Int, data: ByteVector)

  /* TODO: type and length are Big endian right now, should they be little endian? */
  implicit val hcMessage: Codec[HcMessage] = {
    ("type"     | uint16) ::
    (("length"  | uint16) >>:~ { length =>
    ("message"  | bytes(length)).hlist
  })}.as[HcMessage]

  //Why are we self-reporting the port?
  def HCAuthMessage(uuid: UUID, nickname: String, port: Int) = {
    val authPayload = List(AuthPayload.AuthTypeUUID(uuid),
                          AuthPayload.AuthTypeNickname(nickname),
                          AuthPayload.AuthTypePort(port)).
      foldLeft(hex"")((x,y) => x ++ AuthPayload.codec.encode(Right(y)).require.bytes)
    /* DEBUG: fuck with the bytes to simulate an unrecognized authType */
    //val debugLoad = authPayload.dropRight(4) ++ hex"07020014"
    //println(s"authPayload: $authPayload")
    //HcMessage(MsgTypeShakeAuth, authPayload.length.toInt, debugLoad)
    HcMessage(MsgTypeShakeAuth, authPayload.length.toInt, authPayload)
  }

  val ipv4Codec: TupleCodec[ByteVector, Int] = bytes(4) ~~ uint16
  val ipv6Codec: TupleCodec[ByteVector, Int] = bytes(16) ~~ uint16

  val pex4PayloadCodec: Codec[Vector[(ByteVector, Int)]] = vector(ipv4Codec).complete
  val pex6PayloadCodec: Codec[Vector[(ByteVector, Int)]] = vector(ipv6Codec).complete

  /* TODO: consistent nomenclature */
  val MsgTypeVersion = 0

  val MsgTypeShakeAuth = 10
  val MsgTypeShakeDisconnect = 11

  val MsgTypePing = 2

  val MsgTypePex4 = 30
  val MsgTypePex6 = 31

  /* TODO: */
  val MsgTypeRequestAudio = 410
  val MsgTypeRefuseAudio = 411
  val MsgTypeRequestPex = 420
  val MsgTypeRefusePex = 421

  val MsgTypeLobbyInfo = 5

  val MsgTypeChangeNickname = 610

  val MsgTypeText = 8

  val MsgTypeAudio = 9

  /* Constant Messages */
  val HcDisconnect = Codec.encode(HcMessage(MsgTypeShakeDisconnect, 0, hex"")).require
}

/* Different kinds of audio encodings, how to handle them?? */
object AudioEncoding {
  sealed trait AudioEncoding
  case object Pcm extends AudioEncoding
  case object Opus extends AudioEncoding

  implicit val codec: Codec[AudioEncoding] = mappedEnum(uint(7),
    Opus -> 1,
    Pcm -> 2,
  )
}

case class AudioData(encoding: AudioEncoding.AudioEncoding, endOfSegment: Boolean, data: ByteVector)
object AudioData {
  implicit val codec: Codec[AudioData] = {
    ("encoding" | Codec[AudioEncoding.AudioEncoding]) ::
    ("endOfSegment" | bool) ::
    ("data" | bytes).hlist
  }.as[AudioData]
}


/* Good example of TLV encoding, but not necessary for Authenticate message
 */
object AuthPayload {
  val tUUID = 0
  val tLobbyName = 1
  val tNickname = 2
  val tPort = 3

  type AuthPair = Either[UnrecognizedType, AuthType]

  sealed trait AuthType
  case class AuthTypeUUID(uuid: UUID) extends AuthType
  case class AuthTypeLobbyName(lobbyName: String) extends AuthType
  case class AuthTypeNickname(nickname: String) extends AuthType
  case class AuthTypePort(port: Int) extends AuthType

  case class UnrecognizedType(authType: Int, data: BitVector)

  implicit val uuidCodec: Codec[AuthTypeUUID] = uuid.as[AuthTypeUUID]
  implicit val lobbyNameCodec: Codec[AuthTypeLobbyName] = utf8.as[AuthTypeLobbyName]
  implicit val nicknameCodec: Codec[AuthTypeNickname] = utf8.as[AuthTypeNickname]
  implicit val portCodec: Codec[AuthTypePort] = uint16.as[AuthTypePort]
  implicit val unrecognizedCodec: Codec[UnrecognizedType] = (uint8 :: variableSizeBytes(uint8, bits)).as[UnrecognizedType]

  implicit val authTypeDiscriminated: Discriminated[AuthType, Int] = Discriminated[AuthType, Int](uint8, new CodecTransformation {
    def apply[X](c: Codec[X]) = variableSizeBytes(uint8, c)
  })
  implicit val t: Discriminator[AuthType, AuthTypeUUID, Int] = Discriminator(tUUID)
  implicit val lobbyNameDiscriminator: Discriminator[AuthType, AuthTypeLobbyName, Int] = Discriminator(tLobbyName)
  implicit val nicknameDiscriminator: Discriminator[AuthType, AuthTypeNickname, Int] = Discriminator(tNickname)
  implicit val portDiscriminator: Discriminator[AuthType, AuthTypePort, Int] = Discriminator(tPort)

  val codec: Codec[AuthPair] = discriminatorFallback(unrecognizedCodec, Codec[AuthType])
  val vecCodec: Codec[Vector[AuthPair]] = vector(codec).complete

  /* probably want to make a class with this as a method, but w/e man
     Also, its kind of shitty that we have this big case statement, basically doubling up logic
     Don't know a way to use scodec to create a map-like structure
     This is a useless method
  */
  def toMap(payload: Vector[AuthPair]) = {
    payload.foldLeft(Map.empty[Int, AuthPair]) ((x,y) =>
      x + (y match {
        case Right(x) => x match {
          case AuthTypeUUID(_) => (tUUID -> Right(x))
          case AuthTypeLobbyName(_) => (tLobbyName -> Right(x))
          case AuthTypeNickname(_) => (tNickname -> Right(x))
          case AuthTypePort(_) => (tPort -> Right(x))
        }
        case Left(UnrecognizedType(authType, bv)) => (authType -> Left(UnrecognizedType(authType, bv)))
      })
    )
  }
}
