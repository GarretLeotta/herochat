package herochat.ui

import akka.actor.{ActorRef}

import scalafx.Includes._
import scalafx.beans.property.ObjectProperty
import scalafx.collections.ObservableBuffer
import scalafx.geometry.{Pos, HPos, Insets}
import scalafx.scene.Node
import scalafx.scene.input.MouseEvent
import scalafx.scene.layout.{VBox, HBox}
import scalafx.scene.control.{Button, TextField}
import scalafx.scene.text.{Font, FontWeight, Text}

import javafx.event.ActionEvent

import javax.sound.sampled.{Mixer}

import herochat.{User, ChatMessage, HcView}
import herochat.actors.BigBoss
import herochat.SnakeController.ToModel

class OptionsUserPane(var localUser: ObjectProperty[User])(implicit val viewActor: ActorRef) extends VBox {
  //style = "-fx-background-color: lightgreen"
  spacing = 10
  padding = Insets(20)
  children = Array(
    new Text("Me") {
      font = Font.font(null, FontWeight.Bold, 18)
      alignmentInParent = Pos.CenterLeft
    },
    new Text("Nickname: "),
    new HBox {
      //style = "-fx-background-color: lightgreen"
      spacing = 10
      padding = Insets(20)
      children = nicknameForm()
    }
  )

  def nicknameForm(): Array[Node] = {
    val previewText = new Text(localUser.value.nickname)
    localUser.onChange((obsVal, oldVal, newVal) => previewText.text = newVal.nickname)
    val textField = new TextField {
      promptText = "Change Nickname"
      onAction = _ => {
        if (this.text.value.length > 0 && this.text.value.length <= 20) {
          viewActor ! ToModel(BigBoss.SetNickname(localUser.value, this.text.value))
        }
      }
    }
    val submitButton = new Button("Submit") {
      onAction = _ => textField.fireEvent(new ActionEvent())
    }
    Array(
      previewText,
      textField,
      submitButton,
    )
  }
}
