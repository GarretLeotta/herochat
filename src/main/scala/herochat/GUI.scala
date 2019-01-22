package herochat

import scala.collection.JavaConverters._

import akka.actor.{ActorRef}

import scalafx.Includes._
import scalafx.application.JFXApp
import scalafx.application.JFXApp.PrimaryStage
import scalafx.collections.{ObservableBuffer, ObservableMap}
import scalafx.scene.{Scene}
import scalafx.scene.layout.{BorderPane}

import herochat.ui.{TitlePane, LobbyPane, ChatPane, ServerPane, TestButtonPane}


case class ChatMessage(sender: User, msg: String) {
  override def toString = s"$sender: $msg"
}

//UI stuff hopefully
/**
 * TODO: in order to set a OS-global hook for PTT keys, need to use JNI.
 * For now, just use buttons
 */
class HcGUI(localUser: User)(implicit val viewActor: ActorRef) extends JFXApp {
  println(s"gui initialized with view: $viewActor")
  //need somewhere to store old messages for future
  //need to be able to serialize users, detect 1 user across IPs that might change


  var usersInLobby = ObservableBuffer[Peer]()
  var userMap = ObservableMap[User, Peer]()
  var serverList = ObservableBuffer[User]()

  /*
  userMap.onChange { (buf, change) => change match {
    case ObservableMap.Add(key, added) =>
      println(s"test: added: $key, $added")
    case ObservableMap.Remove(key, removed) =>
      println(s"test: removed: $key, $removed")
    case ObservableMap.Replace(key, added, removed) =>
      println(s"test: replaced: $key, $added, $removed")
    case x => println(s"test: unknown change sub: $x")
  }}
  */

  //println(s"class: $getClass")

  var messages = ObservableBuffer[ChatMessage]()

  stage = new PrimaryStage {
    title = "Herochat (Test)"
    width = 1100
    height = 700
    scene = new Scene {
      stylesheets += getClass.getResource("styles.css").toExternalForm
      root = new BorderPane {
        top = new TitlePane().content
        left = new LobbyPane(userMap, localUser).content
        center = new ChatPane(messages).content
        right = new ServerPane(serverList).content
        bottom = new TestButtonPane(localUser).content
      }
    }
  }

  viewActor ! HcView.GuiInitialized
}
