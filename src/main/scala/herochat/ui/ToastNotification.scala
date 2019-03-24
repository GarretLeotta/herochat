package herochat.ui

import scala.language.postfixOps

import scalafx.Includes._
import scalafx.application.Platform
import scalafx.collections.{ObservableBuffer}
import scalafx.geometry.{Orientation, Pos, VPos, HPos, Insets}
import scalafx.scene.Node
import scalafx.scene.control.{Label, Button}
import scalafx.scene.paint.Color
import scalafx.scene.layout.{Pane, FlowPane, VBox, Priority}
import scalafx.scene.text.{Font, Text, TextAlignment}

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
 */
/* TODO: Style all this stuff with css */
class Toast(msg: String, level: Toast.Level, val toastId: Int) extends FlowPane {
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

  val labelWidth = this.maxWidth()

  children = new Label(msg) {
    wrapText = true
    maxWidth = labelWidth
    textAlignment = TextAlignment.Left
    font = Font.font(12)
    //style = "-fx-background-color: rgba(255, 255, 255, 0.7);"
  }
}


class Toaster(timer: Timer) extends Pane {
  styleClass.add("toaster")
  mouseTransparent = true

  val notificationWidth = 200
  val notificationHeight = 500
  val notificationPad = 10

  val delayTime1 = 5000
  val delayTime2 = 2500

  var notis = ObservableBuffer[Toast]()
  notis.onChange( (source, change) => {
    toastPane.children = notis
  })

  /* TODO: Don't really like this */
  var nextToastId = 0
  def addToast(msg: String, level: Toast.Level): Unit = {
    val id = nextToastId
    nextToastId += 1
    val toast = new Toast(msg, level, id) {
      /* TODO: no magic numbers */
      maxWidth = toastPane.maxWidth() - 10
    }
    notis += toast
    timer.schedule(new TimerTask() { def run = {
      Platform.runLater {
        notis.retainAll(notis.filterNot(_.toastId == id))
      }
    }}, delayTime1)
  }

  val toastPane = new VBox() {
    children = notis
    alignment = Pos.BottomCenter
    //style = "-fx-background-color: rgba(255, 255, 255, 0.4);"
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
