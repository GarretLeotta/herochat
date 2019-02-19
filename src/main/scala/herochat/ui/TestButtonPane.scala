package herochat.ui

import akka.actor.{ActorRef}

import scalafx.Includes._
import scalafx.beans.property.{ObjectProperty, StringProperty}
import scalafx.collections.ObservableBuffer
import scalafx.geometry.{Pos, Insets}
import scalafx.scene.Scene
import scalafx.scene.control.{ButtonBar, Button, TextField}
import scalafx.scene.input.{Clipboard, ClipboardContent}
import scalafx.scene.text.{Font, FontWeight, Text}
import scalafx.scene.layout.{HBox, VBox}
import scalafx.stage.Stage

import javafx.event.ActionEvent


import herochat.{Peer, ChatMessage, HcView}
import herochat.actors.BigBoss
import herochat.SnakeController.ToModel

class TestButtonPane(
    var localPeer: ObjectProperty[Peer],
    var joinLink: StringProperty
  )(implicit val viewActor: ActorRef) {
  def msgButton(text: String, msg: Any) = {
    new Button(text) {
      onAction = (event: ActionEvent) =>  {
        viewActor ! msg
      }
    }
  }

  /* check if user inputted a URL or an IP
   * TODO: experiment with non-blocking dialog
   * TODO: make these pattern matches less forgiving?
   */
  val connButton = new Button("Connect") {
    onAction = (event: ActionEvent) => {
      viewActor ! ToModel(BigBoss.GetJoinLink)
      val dialog = new ConnectionDialog()
      dialog.showAndWait().foreach(viewActor ! HcView.ConnectString(_))
    }
  }

  val inviteButton = new Button("Invite") {
    val inviteText = new TextField {
      text <== joinLink
      editable = false
      minWidth = 300
    }
    onAction = (event: ActionEvent) => {
      viewActor ! ToModel(BigBoss.GetJoinLink)
      val stage = new Stage {
        title = "Invite Your Friends with this one weird trick!"
        resizable = false
        scene = new Scene(400, 200) {
          root = new VBox {
            spacing = 10
            padding = Insets(20)
            children = Array(
              new Text("Give this link to your buddies"),
              new HBox {
                spacing = 10
                children = Array(
                  inviteText,
                  new Button("Copy") {
                    onAction = (event: ActionEvent) => {
                      val clipboard = Clipboard.systemClipboard
                      val content = new ClipboardContent()
                      content.putString(inviteText.text())
                      clipboard.content = content
                    }
                  },
                )
              },
            )
          }
        }
      }
      stage.show()
    }
  }


  val content = new ButtonBar {
    buttons = ObservableBuffer[Button](
      msgButton("Settings", HcView.ShowOptions),
      inviteButton,
      connButton,
      msgButton("Disconnect", HcView.DisconnectFromLobby),
      msgButton("Mute", ToModel(BigBoss.SetMuteUser(localPeer().id, true))),
      msgButton("UnMute", ToModel(BigBoss.SetMuteUser(localPeer().id, false))),
      msgButton("Speak", ToModel(BigBoss.StartSpeaking)),
      msgButton("Don't speak", ToModel(BigBoss.StopSpeaking)),
    )
    buttonOrder = "U+"
    padding = Insets(5)
  }
}
