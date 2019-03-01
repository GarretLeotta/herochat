package herochat.ui

import akka.actor.{ActorRef}

import scalafx.Includes._
import scalafx.beans.property.{ObjectProperty, DoubleProperty}
import scalafx.collections.{ObservableBuffer, ObservableMap}
import scalafx.geometry.{Pos, Insets}
import scalafx.scene.layout.{VBox, BorderPane, Pane}
import scalafx.scene.control.{Button}

import javafx.event.ActionEvent

import javax.sound.sampled.{Mixer}

import herochat.{Peer, ChatMessage, HcView, Settings}
import herochat.actors.BigBoss
import herochat.SnakeController.ToModel

class OptionsPane(
    var localPeer: ObjectProperty[Peer],
    var pttShortcut: ObjectProperty[Settings.KeyBinding],
    var pttDelay: DoubleProperty,
  )(implicit val viewActor: ActorRef) extends BorderPane {

  val audioTab = new OptionsAudioPane()
  val userTab = new OptionsUserPane(localPeer)
  val shortcutTab = new OptionsShortcutPane(pttShortcut, pttDelay)

  var selectedTab =  ObjectProperty[Pane](this, "selectedTab", userTab)
  var activeTabs = new ObservableBuffer[Tuple2[String, Pane]]()
  activeTabs.append(
    ("User", userTab),
    ("Audio", audioTab),
    ("Shortcuts", shortcutTab),
  )


  def tabButton(tup: Tuple2[String, Pane]): Button = {
    new Button(tup._1) {
      onAction = (event: ActionEvent) =>  {
        selectedTab.value = tup._2
        center = tup._2
      }
    }
  }

  def onStartup(): Unit = {
    shortcutTab.onStartup()
  }

  left = new VBox {
    style = "-fx-background-color: skyblue"
    prefWidth = 240
    spacing = 10
    padding = Insets(20)
    children = activeTabs.map(tabButton)
  }
  center = selectedTab.value
  right = new OptionsExitPane().content
}


class OptionsExitPane()(implicit val viewActor: ActorRef) {
  val content = new VBox {
    style = "-fx-background-color: lightblue"
    prefWidth = 80
    padding = Insets(20)
    children = Array(
      new Button("Exit") {
        onAction = (event: ActionEvent) =>  {
          viewActor ! HcView.ShowDefault
          viewActor ! ToModel(BigBoss.SaveSettings)
        }
      }
    )
  }
}
