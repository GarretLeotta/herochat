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
object HcCodec {
  import GCodecs._

  sealed trait HcMessage

  //case class AuthPayloadWithLobby(uuid: UUID, nickname: String, port: Int, lobbyId: UUID) extends HcPayload
  /* TODO change port to inetsocketaddress */
  case class HcAuthMessage(uuid: UUID, port: Int, nickname: String) extends HcMessage
  implicit val hcAuthMessage: Codec[HcAuthMessage] = {
    ("uuid"     | uuid) ::
    ("port"     | uint16) ::
    //max nickname size is 255
    ("nickname" | variableSizeBytes(uint8, utf8)).hlist
  }.as[HcAuthMessage]
  case object HcShakeDisconnectMessage extends HcMessage

  case class HcPingMessage(timestamp: Instant) extends HcMessage
  implicit val hcPingMessage: Codec[HcPingMessage] = instant.as[HcPingMessage]

  case class HcPex4Message(addresses: Vector[(Inet4Address, Int)]) extends HcMessage
  implicit val hcPex4Message: Codec[HcPex4Message] =
    vector(inet4Address ~~ uint16).complete.as[HcPex4Message]
  case class HcPex6Message(addresses: Vector[(Inet6Address, Int)]) extends HcMessage
  implicit val hcPex6Message: Codec[HcPex6Message] =
    vector(inet6Address ~~ uint16).complete.as[HcPex6Message]
  /* TODO: import inetaddress encoding, extend changeAddress to use inet6 */
  case class HcPexChangeAddressMessage(address: Inet6Address, port: Int) extends HcMessage
  implicit val hcPexChangeAddressMessage: Codec[HcPexChangeAddressMessage] =
    (inet6Address :: uint16).as[HcPexChangeAddressMessage]

  case class HcChangeNicknameMessage(newName: String) extends HcMessage
  implicit val hcChangeNicknameMessage: Codec[HcChangeNicknameMessage] = utf8.as[HcChangeNicknameMessage]

  case class HcTextMessage(timestamp: Instant, content: String) extends HcMessage
  implicit val hcTextMessage: Codec[HcTextMessage] = {
    ("timestamp" | instant) ::
    ("content"   | utf8).hlist
  }.as[HcTextMessage]
  case class HcAudioMessage(audio: AudioData) extends HcMessage
  implicit val hcAudioMessage: Codec[HcAudioMessage] = Codec[AudioData].as[HcAudioMessage]


  /* TODO: consistent nomenclature */
  val MsgTypeVersion = 0

  val MsgTypeShakeAuth = 10
  val MsgTypeShakeDisconnect = 11

  val MsgTypePing = 2

  val MsgTypePex4 = 304
  val MsgTypePex6 = 306
  val MsgTypePexChangeAddress = 316

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
  implicit val hcPingDiscriminator: HcDiscriminator[HcPingMessage] = Discriminator(MsgTypePing)
  implicit val hcPex4Discriminator: HcDiscriminator[HcPex4Message] = Discriminator(MsgTypePex4)
  implicit val hcPex6Discriminator: HcDiscriminator[HcPex6Message] = Discriminator(MsgTypePex6)
  implicit val hcPexChangeDiscriminator: HcDiscriminator[HcPexChangeAddressMessage] = Discriminator(MsgTypePexChangeAddress)
  implicit val hcChangeNicknameDiscriminator: HcDiscriminator[HcChangeNicknameMessage] = Discriminator(MsgTypeChangeNickname)
  implicit val hcTextDiscriminator: HcDiscriminator[HcTextMessage] = Discriminator(MsgTypeText)
  implicit val hcAudioDiscriminator: HcDiscriminator[HcAudioMessage] = Discriminator(MsgTypeAudio)
}

/* Different kinds of PCM audio encodings, how to handle them?? */
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
