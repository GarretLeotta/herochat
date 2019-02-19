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

import herochat.{ChatMessage, HcView}
import herochat.actors.BigBoss
import herochat.SnakeController.ToModel


class OptionsAudioPane()(implicit val viewActor: ActorRef) extends VBox {
  //style = "-fx-background-color: lightgreen"
  spacing = 10
  padding = Insets(20)

  var inMixers = ObservableBuffer[Mixer.Info]()
  var selectedInMixer = ObjectProperty[Mixer.Info](this, "selectedInputMixer")
  var outMixers = ObservableBuffer[Mixer.Info]()
  var selectedOutMixer = ObjectProperty[Mixer.Info](this, "selectedOutputMixer")

  def mixerComboBox(
      mixers: ObservableBuffer[Mixer.Info],
      currentMixer: ObjectProperty[Mixer.Info],
      msgFunc: Mixer.Info => Any): ComboBox[Mixer.Info] = {
    val comboBox = new ComboBox[Mixer.Info] {
      items = mixers
      cellFactory = { p =>
        new ListCell[Mixer.Info] {
          item.onChange { (obsVal, oldVal, newVal) => {
            Option(newVal) match {
              case Some(mixer) => text = newVal.toString
              case None => text = null
            }
          }}
        }
      }
      onAction = {ae: ActionEvent =>
        currentMixer.update(this.value.value)
        viewActor ! msgFunc(this.value.value)
      }
    }
    currentMixer.onChange { (obsVal, oldVal, newVal) => {
      comboBox.value = newVal
    }}
    comboBox
  }
  val inMixerSelectBox = mixerComboBox(inMixers, selectedInMixer, ((mixer: Mixer.Info) => ToModel(BigBoss.SetInputMixer(mixer))))
  val outMixerSelectBox = mixerComboBox(outMixers, selectedOutMixer, ((mixer: Mixer.Info) => ToModel(BigBoss.SetOutputMixer(mixer))))


  val audioSettings = new GridPane {
    hgap = 10
    vgap = 10
    padding = Insets(10)
    gridLinesVisible = true
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

    add(new Label("Audio Settings") {
      style = "-fx-background-color: lavender"
    }, 0, 0, 2, 1)
    add(new Label("Input Device") {
      style = "-fx-background-color: khaki"
    }, 0, 1)
    add(new Label("Output Device") {
      style = "-fx-background-color: lightpink"
    }, 1, 1)
    add(inMixerSelectBox, 0, 2)
    add(outMixerSelectBox, 1, 2)
  }

  children = Array(
    audioSettings,
  )
}
