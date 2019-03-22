package herochat.ui

import scala.language.postfixOps
import scala.concurrent.duration._

import akka.actor.{ActorRef}

import scalafx.Includes._
import scalafx.beans.binding.Bindings
import scalafx.beans.property.{ObjectProperty, StringProperty, DoubleProperty}
import scalafx.collections.ObservableBuffer
import scalafx.geometry.{Pos, HPos, Insets}
import scalafx.scene.Node
import scalafx.scene.layout.{HBox, VBox, GridPane, ColumnConstraints}
import scalafx.scene.control.{TextField, Button, Label, ComboBox, ListCell, ListView, Slider}
import scalafx.scene.text.{Font, FontWeight, Text}
import scalafx.stage.WindowEvent

import javafx.event.ActionEvent

import javax.sound.sampled.{Mixer}

import ghook.GlobalHook

import herochat.{Peer, ChatMessage, HcView, Settings}
import herochat.actors.BigBoss
import herochat.SnakeController.ToModel

/* TODO: create class that display options in a grid */
/**
 * Really weird that this class handles shortcuts, but for now, whatever..
 * TODO: Bindings and properties are handled badly here. Lot of bugs if I don't change pttShortcut
 * in a specific way. Will need to revisit this code if I want to add any other way to change PTT
 */
class OptionsShortcutPane(
    var pttShortcut: ObjectProperty[Settings.KeyBinding],
    var pttDelay: DoubleProperty,
  )(implicit val viewActor: ActorRef) extends VBox {
  //style = "-fx-background-color: lightgreen"
  spacing = 10
  padding = Insets(20)

  val pttButtonText = Bindings.createStringBinding(() => Option(pttShortcut()).map(_.keyCode.toString).getOrElse(""), pttShortcut)

  val globalHook = new GlobalHook(
    () => viewActor ! ToModel(BigBoss.StartSpeaking),
    () => viewActor ! ToModel(BigBoss.StopSpeaking)
  )

  /** This is an atrocity, need a common type for inputDevice & keyCode across ghook and herochat.
   *  Should use scalafx, but scalafx doesn't support mouse4 & mouse5.
   */
  def onStartup(): Unit = {
    /* TODO: toShort can fail, need to handle that */
    Option(pttShortcut()).map(x => globalHook.registerHookWithCode(x.hid.toString()(0), x.keyCode.toShort))
  }

  /* TODO: convert meaningless numbers to human-readable keys/mouse buttons */
  globalHook.hookProp.onChange { (obsVal, oldVal, newVal) => {
    newVal.hid match {
      case GlobalHook.Keyboard =>
        //pttButtonText.update(newVal.keyCode.toString)
        val binding = Settings.KeyBinding(Settings.Keyboard, newVal.keyCode)
        pttShortcut.update(binding)
        viewActor ! ToModel(BigBoss.SetPTTShortcut(binding))
      case GlobalHook.Mouse =>
        //pttButtonText.update(newVal.keyCode.toString)
        val binding = Settings.KeyBinding(Settings.Mouse, newVal.keyCode)
        pttShortcut.update(binding)
        viewActor ! ToModel(BigBoss.SetPTTShortcut(binding))
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

  val pttDelaySlider = new Slider(0, 1000, pttDelay()) {
    majorTickUnit = 1
    snapToTicks = true
    value <==> pttDelay
    value.onChange { (obsVal, oldVal, newVal) => {
      viewActor ! ToModel(BigBoss.SetPTTDelay(newVal.intValue milliseconds))
    }}
  }

  val grid = new GridPane {
    hgap = 10
    vgap = 10
    padding = Insets(10)
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
    new Label("Shortcuts") {
      font = Font.font(null, FontWeight.Bold, 18)
      alignmentInParent = Pos.CenterLeft
    },
    grid
  )
}
