package herochat.ui

import scala.language.postfixOps

import scalafx.Includes._
import scalafx.animation.{KeyFrame, KeyValue, Timeline}
import scalafx.application.Platform
import scalafx.collections.{ObservableBuffer}
import scalafx.geometry.{Orientation, Pos, VPos, HPos, Insets}
import scalafx.scene.Node
import scalafx.scene.control.{Label, Button}
import scalafx.scene.input.{MouseEvent}
import scalafx.scene.paint.Color
import scalafx.scene.layout.{Pane, FlowPane, VBox, Priority}
import scalafx.scene.text.{Font, Text, TextAlignment}
import scalafx.util.Duration

import java.util.{Timer, TimerTask}

object Toast {
  sealed trait Level
  case object Success extends Level
  case object Info extends Level
  case object Warning extends Level
  case object Error extends Level

  /* TODO: DEBUG: remove this */
  val levels = List(Success, Info, Warning, Error)
}

/** Toasts should all appear as the same width. The text within should be right-aligned
 * TODO: once a toast starts fading out, its gone, hovering on it should fade it back in
 */
/* TODO: Style all this stuff with css */
class Toast(
    msg: String,
    level: Toast.Level,
    initialStayTime: Int,
    unfocusedStayTime: Int,
    container: Toaster,
    timer: Timer,
  ) extends FlowPane { self =>
  import Toast._
  level match {
    case Success => styleClass += ("toast", "toast-success")
    case Info => styleClass += ("toast", "toast-info")
    case Warning => styleClass += ("toast", "toast-warning")
    case Error => styleClass += ("toast", "toast-error")
  }

  vgrow = Priority.Never
  hgrow = Priority.Never
  padding = Insets(3)

  val fadeoutTime = 500
  val labelWidth = this.maxWidth()
  children = new Label(msg) {
    mouseTransparent = true
    wrapText = true
    maxWidth = labelWidth
    textAlignment = TextAlignment.Left
    font = Font.font(12)
  }

  var canDelete = true

  timer.schedule(new TimerTask() { def run = {
    if (canDelete) {
      fadeout()
    }
  }}, initialStayTime)

  def fadeout(): Unit = {
    val fadeOutKey1 = KeyFrame(
      time = new Duration(fadeoutTime),
      values = Set(KeyValue(opacity, 0))
    )
    val fadeOutTimeline = new Timeline() {
      keyFrames.add(fadeOutKey1)
      onFinished = { () => container.removeToast(self) }
    }
    fadeOutTimeline.play()
  }
  onMouseEntered = { me: MouseEvent =>
    canDelete = false
  }
  onMouseExited = { me: MouseEvent =>
    canDelete = true
    timer.schedule(new TimerTask() { def run = {
      if (canDelete) {
        fadeout()
      }
    }}, unfocusedStayTime)
  }
}


/* Timer should be implicit */
class Toaster(timer: Timer) extends Pane {
  styleClass.add("toaster")
  pickOnBounds = false

  val notificationWidth = 200
  val notificationHeight = 500
  val notificationPad = 10

  var notis = ObservableBuffer[Toast]()
  notis.onChange( (source, change) => {
    toastPane.children = notis
  })

  def addToast(msg: String, level: Toast.Level): Unit = {
    val toast = new Toast(msg, level, 5000, 1000, this, timer) {
      /* TODO: no magic numbers */
      maxWidth = toastPane.maxWidth() - 10
    }
    notis += toast
  }
  def removeToast(toast: Toast): Unit = {
    notis -= toast
  }

  val toastPane = new VBox() {
    pickOnBounds = false
    children = notis
    alignment = Pos.BottomCenter
    spacing = 5
    padding = Insets(5)
    minWidth = notificationWidth
    maxWidth = notificationWidth
    minHeight = notificationHeight
    maxHeight = notificationHeight
  }

  children = Seq(toastPane)

  def translateToastPane(): Unit = {
    toastPane.translateX = (this.width() - notificationWidth - notificationPad)
    toastPane.translateY = (this.height() - notificationHeight - notificationPad)
  }

  width.addListener( (obs, oldVal, newVal) => { translateToastPane() })
  height.addListener( (obs, oldVal, newVal) => { translateToastPane() })
}
