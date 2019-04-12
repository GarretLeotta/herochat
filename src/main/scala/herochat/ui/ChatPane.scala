package herochat.ui

import util.Properties

import akka.actor.{ActorRef}

import scalafx.Includes._
import scalafx.collections.ObservableBuffer
import scalafx.geometry.Pos
import scalafx.scene.control.{ListCell, ListView, ScrollPane, TextArea}
import scalafx.scene.input.{KeyCode, KeyEvent, MouseEvent}
import scalafx.scene.layout.{BorderPane, FlowPane, VBox}
import scalafx.scene.Node
import scalafx.scene.text.{Font, FontWeight, Text}


import herochat.{HcView, Peer}


/* TODO: enrich with support for images, emotes, videos, music */
case class ChatMessage(sender: Peer, msg: String) {
  override def toString = s"${sender.nickname}: $msg"
  def renderUI(): Node = {
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
}

class ChatPane(
    val messages: ObservableBuffer[ChatMessage]
  )(implicit val viewActor: ActorRef) extends BorderPane {

  val messagesBox = new VBox {
    children = messages.map(_.renderUI)
    spacing = 10
  }

  /* TODO: scrollbar is weird before there's any content in box
   * TODO: also, some weirdness before the pane is full, vvalue increases as content grows downwards
   */
  val chatScroll = new ScrollPane {
    vbarPolicy = ScrollPane.ScrollBarPolicy.Always
    hbarPolicy = ScrollPane.ScrollBarPolicy.Never
    content = messagesBox
  }
  center = chatScroll

  bottom = new FlowPane {
    children = new TextArea() {
      promptText = "Send a Message!"
      alignmentInParent = Pos.CenterLeft
      editable = true
      wrapText = true
      prefHeight = 100
      onKeyPressed = ((k: KeyEvent) => {
        if (k.code == KeyCode.Enter) {
          if (k.shiftDown) {
            text = (text() + Properties.lineSeparator)
            this.delegate.positionCaret(text().length)
          } else {
            if (text().trim.length > 0) {
              //println(s"${text().length}:${text()}")
              viewActor ! HcView.SendMessage(text().trim)
              text = ""
              this.delegate.positionCaret(0)
            }
            k.consume()
          }
        }
      })
    }
  }

  //one problem here, is that we re-render the whole thing, whatever
  messages.onChange( (source, change) => {
    /* TODO: fix this, doesn't work. Think that the children is updating after the scroll vvalue updates */
    //If scrolled to the bottom of chat history, scroll down after new messages come in
    val clampScroll = chatScroll.vvalue() == chatScroll.vmax()
    //println(s"${chatScroll.vvalue()} ${chatScroll.vmax()} $clampScroll")
    messagesBox.children = source.map(_.renderUI)
    if (clampScroll) {
      chatScroll.vvalue = chatScroll.vmax()
    }
  })
}
