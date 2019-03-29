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

import herochat.{ChatMessage, HcView, Settings}
import herochat.actors.BigBoss
import herochat.SnakeController.ToModel


class OptionsAudioPane(settings: Settings)(implicit val viewActor: ActorRef) extends VBox {
  spacing = 10
  padding = Insets(20)

  val inMixers = ObservableBuffer[Mixer.Info]()
  val selectedInMixer = ObjectProperty[Mixer.Info](this, "selectedInputMixer")
  val outMixers = ObservableBuffer[Mixer.Info]()
  val selectedOutMixer = ObjectProperty[Mixer.Info](this, "selectedOutputMixer")

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
      value <==> currentMixer
      onAction = { ae: ActionEvent =>
        viewActor ! msgFunc(this.value())
      }
    }
    comboBox
  }
  val inMixerSelectBox = mixerComboBox(inMixers, selectedInMixer, ((mixer: Mixer.Info) => ToModel(BigBoss.SetInputMixer(mixer))))
  val outMixerSelectBox = mixerComboBox(outMixers, selectedOutMixer, ((mixer: Mixer.Info) => ToModel(BigBoss.SetOutputMixer(mixer))))


  val audioSettings = new GridPane {
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
    add(new Label("Input Device"), 0, 0)
    add(new Label("Output Device"), 1, 0)
    add(inMixerSelectBox, 0, 1)
    add(outMixerSelectBox, 1, 1)
  }

  children = Array(
    new Label("Audio Settings") {
      font = Font.font(null, FontWeight.Bold, 18)
      alignmentInParent = Pos.CenterLeft
    },
    audioSettings,
  )
}
