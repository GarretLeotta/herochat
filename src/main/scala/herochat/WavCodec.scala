package herochat

import javax.sound.sampled.AudioFormat

import scodec.{Codec, CodecTransformation, SizeBound}
import scodec.bits._
import scodec.codecs._

object WavCodec {
  /*
   * Need to append this constant to front of WAV chunks
   * ("riff_id" | constant(hex"52494646")) "RIFF"
   * ("wave_id" | constant(hex"57415645")) "WAVE"
   * ("fmt_id" | constant(hex"666d7420")) "fmt "
   * ("data_id" | constant(hex"64617461")) "data"
   */

  case class RiffChunk(chunkId: String, chunkSize: Long, chunkData: ByteVector)
  case class PcmFormat(
    format: AudioFormat.Encoding,
    channels: Int,
    samplesPerSec: Long,
    bytesPerSec: Long,
    blockAlign: Int,
    bitsPerSample: Int
  ) {
    def toJavax: AudioFormat = {
      new AudioFormat(format, samplesPerSec, bitsPerSample, channels, blockAlign, samplesPerSec, false)
    }
  }

  implicit val chunkCodec: Codec[RiffChunk] = {
    ("id"      | fixedSizeBytes(4, ascii)) ::
    (("size"   | uint32L) >>:~ { size =>
      /* Pad an extra byte if size is odd */
      val padded_size = size + size % 2
      ("data"  | fixedSizeBytes(padded_size, bytes)).hlist
    })
  }.as[RiffChunk]

  implicit val formatCodec: Codec[AudioFormat.Encoding] = mappedEnum(uint16L,
    AudioFormat.Encoding.PCM_SIGNED -> 1,
    AudioFormat.Encoding.PCM_FLOAT -> 3,
    AudioFormat.Encoding.ALAW -> 6,
    AudioFormat.Encoding.ULAW -> 7,
  )

  implicit val fmtChunkCodec: Codec[PcmFormat] = {
    ("waveId"        | constant(hex"57415645")) ::
    ("chunkId"       | constant(hex"666d7420")) ::
    ("size"          | constant(hex"10000000")) ::
    ("fmtTag"        | Codec[AudioFormat.Encoding]) ::
    ("channels"      | uint16L) ::
    ("samplesPerSec" | uint32L) ::
    ("bytesPerSec"   | uint32L) ::
    ("blockAlign"    | uint16L) ::
    ("bitsPerSample" | uint16L)
  }.as[PcmFormat]

  def wavHeader(pcmFormat: PcmFormat): ByteVector = {
    val fmtChunk = Codec.encode(pcmFormat).require.bytes
    Codec.encode(RiffChunk("RIFF", fmtChunk.length, fmtChunk)).require.bytes
  }
}
