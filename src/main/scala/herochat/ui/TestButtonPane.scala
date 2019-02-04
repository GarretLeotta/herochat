package herochat.ui

import akka.actor.{ActorRef}

import scalafx.Includes._
import scalafx.collections.ObservableBuffer
import scalafx.geometry.{Pos, Insets}
import scalafx.scene.control.{ButtonBar, Button, TextInputDialog}

import javafx.event.ActionEvent


import herochat.{User, ChatMessage, HcView}
import herochat.actors.BigBoss
import herochat.SnakeController.ToModel

class TestButtonPane(localUser: User)(implicit val viewActor: ActorRef) {
  def msgButton(text: String, msg: Any) = {
    new Button(text) {
      onAction = (event: ActionEvent) =>  {
        viewActor ! msg
      }
    }
  }

  val dialog = new TextInputDialog(defaultValue = "41331") {
    //need a stage here
    //initOwner(stage)
    title = "Join a Lobby"
    headerText = "Join a Lobby."
    contentText = "Enter Port of lobby(DEBUG):"
  }

  /* check if user inputted a URL or an IP
   * TODO: experiment with non-blocking dialog
   * TODO: make these pattern matches less forgiving?
   */
  val ConnButton = new Button("Connect") {
    onAction = (event: ActionEvent) => {
      val testDialog = new ConnectionDialog()
      testDialog.showAndWait().foreach(viewActor ! HcView.ConnectString(_))
    }
  }


  val content = new ButtonBar {
    buttons = ObservableBuffer[Button](
      msgButton("Settings", HcView.ShowOptions),
      ConnButton,
      msgButton("Disconnect", HcView.DisconnectFromLobby),
      msgButton("Mute", ToModel(BigBoss.SetMuteUser(localUser, true))),
      msgButton("UnMute", ToModel(BigBoss.SetMuteUser(localUser, false))),
      msgButton("Speak", ToModel(BigBoss.StartSpeaking)),
      msgButton("Don't speak", ToModel(BigBoss.StopSpeaking)),
    )
    buttonOrder = "U+"
    padding = Insets(5)
  }
}
