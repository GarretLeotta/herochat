package herochat

import scala.language.postfixOps
import scala.concurrent.duration._
import scala.collection.mutable

import org.json4s._
import org.json4s.native.Serialization

import java.util.UUID

import javax.sound.sampled.{AudioFormat, AudioSystem, Mixer}


/* throws exception if no mixer found */
class MixerInfoSerializer extends CustomSerializer[Mixer.Info] (implicit format => ( {
  case jsonObj: JObject =>
    val name = (jsonObj \ "name").extract[String]
    /* get list mixers from audioSystem, filter based on name */
    AudioSystem.getMixerInfo.find(_.getName == name).get
}, {
  case mixerInfo: Mixer.Info => JObject(
    JField("name", JString(mixerInfo.getName)),
    JField("vendor", JString(mixerInfo.getVendor)),
    JField("version", JString(mixerInfo.getVersion)),
    JField("description", JString(mixerInfo.getDescription))
  )
}
))

class PeerStateSerializer extends CustomSerializer[Peer] (implicit format => ( {
  case jsonObj: JObject =>
    val id = UUID.fromString((jsonObj \ "id").extract[String])
    val name = (jsonObj \ "name").extract[String]
    val muted = (jsonObj \ "muted").extract[Boolean]
    val deafened = (jsonObj \ "deafened").extract[Boolean]
    val volume = (jsonObj \ "volume").extract[Double]
    Peer(id, name, muted, deafened, false, volume)
}, {
  case peerState: Peer => JObject(
    JField("id", JString(peerState.id.toString)),
    JField("name", JString(peerState.nickname)),
    JField("muted", JBool(peerState.muted)),
    JField("deafened", JBool(peerState.deafened)),
    JField("volume", JDouble(peerState.volume))
  )
}
))

class AudioFormatSerializer extends CustomSerializer[AudioFormat.Encoding] (implicit format => ( {
  case jsonObj: JString =>
    val id = jsonObj.extract[String]
    new AudioFormat.Encoding(id)
}, {
  case encoding: AudioFormat.Encoding => JString(encoding.toString)
}
))

class InputDeviceSerializer extends CustomSerializer[Settings.KeyBinding] (implicit format => ( {
  case jsonArr: JArray =>
    val hidString = jsonArr(0).extract[String]
    val keyCode = jsonArr(1).extract[Int]
    Settings.KeyBinding(Settings.hidFromString(hidString), keyCode)
}, {
  case binding: Settings.KeyBinding => JArray(List(
    JString(binding.hid.toString),
    JInt(binding.keyCode),
  ))
}
))

/* TODO: Don't think JString(Serialization.write(x)) is the right way to do this */
class SettingsSerializer extends CustomSerializer[Settings] (implicit format => ( {
  case jsonObj: JObject =>
    val soundSettings = (jsonObj \ "soundSettings").extract[SoundSettings]
    val userSettings = (jsonObj \ "userSettings").extract[Peer]
    val localPort = (jsonObj \ "localPort").extract[Int]
    val pttDelay = (jsonObj \ "pttDelayInMilliseconds").extract[Int] milliseconds
    /* Jesus Christ.. */
    val peerSettings: mutable.Map[UUID, Peer] = (jsonObj \ "peerSettings").extract[JArray]
      .children.map(_.extract[Peer]).foldLeft(mutable.Map[UUID, Peer]()) { (map, peer) =>
        map + (peer.id -> peer)
      }
    val shortcuts = (jsonObj \ "shortcuts").extract[mutable.Map[String, Settings.KeyBinding]]
    new Settings(soundSettings, userSettings, localPort, pttDelay, peerSettings, shortcuts)
}, {
  case settings: Settings => JObject(
    JField("soundSettings", Extraction.decompose(settings.soundSettings)),
    JField("localPort", Extraction.decompose(settings.localPort)),
    JField("userSettings", Extraction.decompose(settings.userSettings)),
    JField("peerSettings", Extraction.decompose(settings.peerSettings.values)),
    JField("pttDelayInMilliseconds", Extraction.decompose(settings.pttDelayInMilliseconds.length)),
    JField("shortcuts", Extraction.decompose(settings.shortcuts)),
  )
}
))
