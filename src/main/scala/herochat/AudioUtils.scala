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
    sbtCompatibilityBlock {
      AudioSystem.getMixerInfo.map(AudioSystem.getMixer(_)).filter(_.isLineSupported(lineInfo))
    }
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

  /* SBT & javax don't work well together. I should make this a macro or something so it isn't included on release */
  def sbtCompatibilityBlock[R](op: => R) = {
    val cl = classOf[javax.sound.sampled.AudioSystem].getClassLoader
    val old_cl: java.lang.ClassLoader = Thread.currentThread.getContextClassLoader
    Thread.currentThread.setContextClassLoader(cl)
    val ret = op
    Thread.currentThread.setContextClassLoader(old_cl)
    ret
  }
}
