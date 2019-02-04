package herochat.ui

import akka.actor.{ActorRef}

import scalafx.Includes._
import scalafx.collections.ObservableBuffer
import scalafx.geometry.Pos
import scalafx.scene.control.{ListCell, ListView}
import scalafx.scene.input.MouseEvent
import scalafx.scene.layout.{VBox, FlowPane}
import scalafx.scene.text.{Font, FontWeight, Text}

import herochat.User

class ServerPane(var users: ObservableBuffer[User])(implicit val viewActor: ActorRef) {
  val title = new Text("\"Server\"") {
    font = Font.font(null, FontWeight.Bold, 12)
    alignmentInParent = Pos.Center
  }

  val server_list = new FlowPane {
    style = "-fx-background-color: forestgreen  "
    prefWidth = 250
    prefHeight = 550

    children = new ListView[User] {
      items = users

      cellFactory = { p =>
        new ListCell[User] {
          item.onChange { (obsVal, oldVal, newVal) => {
            Option(newVal) match {
              case Some(user) => text = newVal.toString
              case None => text = null
            }
          }}
          onMouseClicked = { me: MouseEvent => println(s"Selection Changed: ${text.value}") }
        }
      }
    }
  }

  //val content = server_list
  val content = new VBox {
    children = ObservableBuffer(title, server_list)
  }
}
