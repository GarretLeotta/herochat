package herochat.ui

import akka.actor.{ActorRef}

import scalafx.Includes._
import scalafx.beans.property.ObjectProperty
import scalafx.collections.{ObservableBuffer, ObservableMap}
import scalafx.geometry.{Pos, Insets}
import scalafx.scene.layout.{VBox, BorderPane, Pane}
import scalafx.scene.control.{Button}

import javafx.event.ActionEvent

import javax.sound.sampled.{Mixer}

import herochat.{User, ChatMessage, HcView}
import herochat.actors.BigBoss
import herochat.SnakeController.ToModel

class OptionsPane(var localUser: ObjectProperty[User])(implicit val viewActor: ActorRef) extends BorderPane {

  val audioTab = new OptionsAudioPane()
  val userTab = new OptionsUserPane(localUser)

  var selectedTab =  ObjectProperty[Pane](this, "selectedTab", userTab)
  /* How to Order the tabs?? */
  var activeTabs = ObservableMap[String, Pane](
    "Audio" -> audioTab,
    "User" -> userTab,
  )


  def tabButton(tup: Tuple2[String, Pane]): Button = {
    new Button(tup._1) {
      onAction = (event: ActionEvent) =>  {
        selectedTab.value = tup._2
        center = tup._2
      }
    }
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
        }
      }
    )
  }
}
