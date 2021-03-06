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

import herochat.{Peer, HcView, Settings}
import herochat.actors.BigBoss
import herochat.SnakeController.ToModel

class OptionsPane(
    settings: Settings,
    val localPeer: ObjectProperty[Peer],
    val pttShortcut: ObjectProperty[Settings.KeyBinding],
    val pttDelay: DoubleProperty,
  )(implicit val viewActor: ActorRef) extends BorderPane {

  val audioTab = new OptionsAudioPane(settings)
  val userTab = new OptionsUserPane(localPeer)
  val shortcutTab = new OptionsShortcutPane(pttShortcut, pttDelay)
  val networkTab = new OptionsNetworkPane(settings)

  val selectedTab =  ObjectProperty[Pane](this, "selectedTab", userTab)
  val activeTabs = new ObservableBuffer[Tuple2[String, Pane]]()
  activeTabs.append(
    ("User", userTab),
    ("Audio", audioTab),
    ("Shortcuts", shortcutTab),
    ("Network", networkTab),
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
  center = selectedTab()
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
