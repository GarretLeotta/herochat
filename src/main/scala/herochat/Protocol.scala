package herochat


import scodec.{Codec, CodecTransformation, SizeBound}
import scodec.bits._
import scodec.codecs._

import java.util.UUID

/*
 */
object HcCodec {
  //Basic Protocol description
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
