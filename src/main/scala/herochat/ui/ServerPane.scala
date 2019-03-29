package herochat.ui

import akka.actor.{ActorRef}

import scalafx.Includes._
import scalafx.collections.ObservableBuffer
import scalafx.geometry.Pos
import scalafx.scene.control.{Label, ListCell, ListView}
import scalafx.scene.input.MouseEvent
import scalafx.scene.layout.{VBox, FlowPane}
import scalafx.scene.text.{Font, FontWeight, Text}

import herochat.Peer

class ServerPane(val users: ObservableBuffer[Peer])(implicit val viewActor: ActorRef) {
  val title = new Label("\"Server\"") {
    font = Font.font(null, FontWeight.Bold, 12)
    alignmentInParent = Pos.Center
  }

  val server_list = new FlowPane {
    style = "-fx-background-color: forestgreen  "
    prefWidth = 250
    prefHeight = 550

    children = new ListView[Peer] {
      items = users

      cellFactory = { p =>
        new ListCell[Peer] {
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
