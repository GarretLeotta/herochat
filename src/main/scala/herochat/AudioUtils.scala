package herochat

import javax.sound.sampled.{AudioSystem, Line, Mixer, Control}

object AudioControl {
  case object Mute
  case object Unmute

  case object GetVolume
  case object GetVolumePrecision
  case object GetVolumeBounds
  case class SetVolume(vol: Double)
}


object AudioUtils {
  def getSupportedMixers(lineInfo: Line.Info): Array[Mixer] = {
    AudioSystem.getMixerInfo.map(AudioSystem.getMixer(_)).filter(_.isLineSupported(lineInfo))
  }

  def getMixerInfo(mixName :String): Mixer.Info = {
    val infos = javax.sound.sampled.AudioSystem.getMixerInfo()
    //println("mixers", infos.deep.mkString(", "))
    for(info <- infos) {
      if(info.getName == mixName) {
        return info
      }
    }
    throw new Exception("no mixers")
  }

  /* Don't like this, but it's better than nothing I suppose */
  def getLineControl[T <: Control](line: Line, controlType: Control.Type): Option[T] = {
    try {
      Some(line.getControl(controlType).asInstanceOf[T])
    } catch {
      case iae: IllegalArgumentException => None
    }
  }
}
