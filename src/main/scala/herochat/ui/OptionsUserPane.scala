package herochat.ui

import akka.actor.{ActorRef}

import scalafx.Includes._
import scalafx.beans.property.ObjectProperty
import scalafx.collections.ObservableBuffer
import scalafx.geometry.{Pos, HPos, Insets}
import scalafx.scene.input.MouseEvent
import scalafx.scene.layout.{VBox, GridPane, ColumnConstraints}
import scalafx.scene.control.{Button, Label, ComboBox, ListCell, ListView}
import scalafx.scene.text.{Font, FontWeight, Text}

import javafx.event.ActionEvent

import javax.sound.sampled.{Mixer}

import herochat.{User, ChatMessage, HcView}
import herochat.actors.BigBoss
import herochat.SnakeController.ToModel

class OptionsUserPane()(implicit val viewActor: ActorRef) extends VBox {
  //style = "-fx-background-color: lightgreen"
  spacing = 10
  padding = Insets(20)
  children = Array(
    new Text("Hello User"),
  )
}
