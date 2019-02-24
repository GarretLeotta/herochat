package herochat.ui

import scala.language.postfixOps
import scala.concurrent.duration._

import akka.actor.{ActorRef}

import scalafx.Includes._
import scalafx.beans.property.{ObjectProperty, StringProperty}
import scalafx.collections.ObservableBuffer
import scalafx.geometry.{Pos, HPos, Insets}
import scalafx.scene.Node
import scalafx.scene.layout.{HBox, VBox, GridPane, ColumnConstraints}
import scalafx.scene.control.{TextField, Button, Label, ComboBox, ListCell, ListView, Slider}
import scalafx.scene.text.{Font, FontWeight, Text}

import javafx.event.ActionEvent

import javax.sound.sampled.{Mixer}

import ghook.GlobalHook

import herochat.{Peer, ChatMessage, HcView, Settings}
import herochat.actors.BigBoss
import herochat.SnakeController.ToModel

class OptionsShortcutPane(
    //var pttBinding: Settings.KeyBinding
  )(implicit val viewActor: ActorRef) extends VBox {
  //style = "-fx-background-color: lightgreen"
  spacing = 10
  padding = Insets(20)

  var pttButtonText = new StringProperty(this, "pttButton")//, pttBinding.toString)
  val globalHook = new GlobalHook(
    () => viewActor ! ToModel(BigBoss.StartSpeaking),
    () => viewActor ! ToModel(BigBoss.StopSpeaking)
  )
  /* TODO: convert meaningless numbers to human-readable keys/mouse buttons */
  globalHook.hookProp.onChange { (obsVal, oldVal, newVal) => {
    newVal.hid match {
      case GlobalHook.Keyboard =>
        pttButtonText.update(newVal.keyCode.toString)
        viewActor ! ToModel(BigBoss.SetPTTShortcut(Settings.KeyBinding(Settings.Keyboard, newVal.keyCode)))
      case GlobalHook.Mouse =>
        pttButtonText.update(newVal.keyCode.toString)
        viewActor ! ToModel(BigBoss.SetPTTShortcut(Settings.KeyBinding(Settings.Mouse, newVal.keyCode)))
      case GlobalHook.NoInput => ()
    }
  }}

  val pttBindChooser = new HBox {
    children = Array(
      new TextField {
        text <== pttButtonText
        editable = false
        //need to figure out how to layout stuff better
        minWidth = 260
      },
      new Button("Change") {
        onAction = (event: ActionEvent) => {
          println(s"Rebinding PTT hooks.")
          globalHook.deregisterHook()
          globalHook.registerHook()
        }
      },
    )
  }

  val pttDelaySlider = new Slider(0, 1000, 20) {
    majorTickUnit = 1
    snapToTicks = true
    value.onChange { (obsVal, oldVal, newVal) => {
      viewActor ! ToModel(BigBoss.SetPTTDelay(newVal.intValue milliseconds))
    }}
  }

  val grid = new GridPane {
    hgap = 10
    vgap = 10
    //padding = Insets(10)
    //gridLinesVisible = true
    columnConstraints = List(
      new ColumnConstraints {
        halignment = HPos.Left
        percentWidth = 50
      },
      new ColumnConstraints {
        halignment = HPos.Left
        percentWidth = 50
      }
    )
    add(new Label("PTT Button"), 0, 0, 2, 1)
    add(pttBindChooser, 0, 1)
    add(new Label("PTT Release Delay"), 1, 0, 2, 1)
    add(pttDelaySlider, 1, 1)
  }

  children = Array(
    new Text("Shortcuts") {
      font = Font.font(null, FontWeight.Bold, 18)
      alignmentInParent = Pos.CenterLeft
    },
    grid
  )
}
