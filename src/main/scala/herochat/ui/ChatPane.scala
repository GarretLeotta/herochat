package herochat.ui

import util.Properties

import akka.actor.{ActorRef}

import scalafx.Includes._
import scalafx.collections.ObservableBuffer
import scalafx.geometry.Pos
import scalafx.scene.control.{ListCell, ListView, ScrollPane, TextArea}
import scalafx.scene.input.{KeyCode, KeyEvent, MouseEvent}
import scalafx.scene.layout.{BorderPane, FlowPane, VBox}
import scalafx.scene.text.{Font, FontWeight, Text}


import herochat.{ChatMessage, HcView}


class ChatPane(var messages: ObservableBuffer[ChatMessage])(implicit val viewActor: ActorRef) {
  def renderChatMessage(msg: ChatMessage) = {
    new TextArea() {
      text = msg.toString
      alignmentInParent = Pos.CenterLeft
      //wrappingWidth = 500
      editable = false
      wrapText = true
      //calc height of text
      //this is kind of bad, too general, would rather a different method
      prefHeight = (new Text(msg.toString){wrappingWidth = 500}.layoutBounds().getHeight + 20)
    }
  }

  val messagesBox = new VBox {
    children = messages.map(renderChatMessage)
    spacing = 10
  }
  /* TODO: why is this double wrapped? */
  val messagesPane = new ScrollPane {
    content = new VBox {
      children = messagesBox
      spacing = 10
    }
  }

  //Eventually, we will make a custom ChatMessage javafx.scene.Node, for rich text/multimedia
  val content = new BorderPane {
    center = messagesPane
    bottom = new FlowPane {
      //TODO: make text in text area "ghost"
      children = new TextArea() {
        text = "Message us"
        alignmentInParent = Pos.CenterLeft
        editable = true
        wrapText = true
        prefHeight = 100
        onKeyPressed = ((k: KeyEvent) => {
          if (k.code == KeyCode.Enter) {
            /* TODO: fix this, doesn't really work, caret's all fucked up */
            if (k.shiftDown) {
              text = (text() + Properties.lineSeparator)
            } else {
              viewActor ! HcView.SendMessage(text().trim)
              text = ""
              //TODO: reset caret, this doesn't seem to work. does it need to be in a runLate?
              this.delegate.positionCaret(0)
            }
          }
        })
      }
    }
  }

  val x = new TextArea() {}
  x.positionCaret(0)

  //one problem here, is that we re-render the whole thing, whatever
  messages.onChange( (source, change) => {
    //println(s"messages onChange: ${source.mkString("[", ", ", "]")},, $change")
    messagesBox.children = source.map(renderChatMessage)
  })
}
