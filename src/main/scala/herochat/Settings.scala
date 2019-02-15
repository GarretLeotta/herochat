package herochat

import scala.collection.mutable

import org.json4s._
import org.json4s.native.Serialization

import java.io.{File, FileWriter, FileReader}
import java.util.UUID

/* TODO: should this audio stuff be in another file??? */
import javax.sound.sampled.{AudioFormat, AudioSystem, Mixer, DataLine, SourceDataLine, TargetDataLine}

case class SoundSettings(
    audioFormat: WavCodec.PcmFormat,
    bufferSize: Int,
    inputMixer: Mixer.Info,
    outputMixer: Mixer.Info)

object Settings {
  implicit val formats = DefaultFormats ++ List(
    new MixerInfoSerializer(),
    new AudioFormatSerializer(),
    new PeerStateSerializer(),
    new SettingsSerializer(),
  )

  /* TODO: cross platform */
  val settingsDir = System.getenv("APPDATA")+"\\herochat\\"
  val defaultFilename = "settings.json"
  println(s"default filename: ${settingsDir+defaultFilename}")

  if (new File(settingsDir).mkdirs()) {
    println(s"created Settings directory: $settingsDir")
  }

  def readSettingsFile(filename: Option[String]): Settings = {
    val file = new File(settingsDir + filename.getOrElse(defaultFilename))
    if (file.exists) {
      if (file.isDirectory) {
        throw new IllegalArgumentException(s"Proposed Settings File, ( ${file.getAbsolutePath} ), is a Directory/Folder")
      } else {
        val fileReader = new FileReader(file)
        val settings = Serialization.read[Settings](fileReader)
        fileReader.close()
        settings
      }
    } else {
      /* TODO: should I create settings folder here? */
      val settings = defaultSettings
      settings.writeSettingsFile(filename)
      settings
    }
  }

  def defaultSettings(): Settings = {
    /**
     * input args: audio encoding, sample rate, sampleSizeInBits, channels, frame size, frame rate, big endian,
     * bufferSize, audio device name, output actorm
     */
    val encoding = AudioFormat.Encoding.PCM_SIGNED
    val sampleRate = 44100
    val sampleSize = 16
    val sampleSizeBytes = sampleSize / 8
    val channels = 1
    val audioFormat = WavCodec.PcmFormat(
      encoding,
      channels,
      sampleRate,
      sampleRate * channels * sampleSizeBytes,
      channels * sampleSizeBytes,
      sampleSize
    )
    /* bufSize[InSeconds] = (bufSize / (channels * sampleSizeBytes)) / sampleRate */
    val bufferSize = 24000

    val sourceInfo = new DataLine.Info(classOf[SourceDataLine], audioFormat.toJavax, bufferSize)
    val targetInfo = new DataLine.Info(classOf[TargetDataLine], audioFormat.toJavax, bufferSize)
    val sourceMixes = AudioUtils.getSupportedMixers(sourceInfo).map(_.getMixerInfo)
    val targetMixes = AudioUtils.getSupportedMixers(targetInfo).map(_.getMixerInfo)
    var sourceMixer = sourceMixes(0)
    var targetMixer = targetMixes(0)

    new Settings(
      SoundSettings(audioFormat, bufferSize, targetMixer, sourceMixer),
      Peer(User(UUID.randomUUID, "Norbert"), false, false, false, 1.0),
      localPort = 41330
    )
  }
}


/* Acts as interface to an abstract database containing config for UI and peer default settings
 * Includes: Mixers, Line Format, preferred buffer size,
 * Local User information, Local listening Port, Last saved peer information,
 * TODO: Snakecontroller should initialize Settings object, but what about instances without UI
 */
class Settings(
    var soundSettings: SoundSettings,
    var userSettings: Peer,
    var localPort: Int,
    var peerSettings: mutable.Map[User, Peer] = mutable.Map.empty[User, Peer]) {
  implicit val formats = Settings.formats

  def addPeer(peerState: Peer): Unit = {
    peerSettings(peerState.user) = peerState
  }

  def writeSettingsFile(filename: Option[String]): Unit = {
    val file = new File(Settings.settingsDir + filename.getOrElse(Settings.defaultFilename))
    if (file.isDirectory) {
      throw new IllegalArgumentException(s"Proposed Settings File, ( ${file.getAbsolutePath} ), is a Directory/Folder")
    } else {
      println(s"writing settings file, $peerSettings, ${JString(Serialization.write(peerSettings.values))}")
      val fileWriter = new FileWriter(file)
      Serialization.writePretty(this, fileWriter)
      fileWriter.close()
    }
  }
}
