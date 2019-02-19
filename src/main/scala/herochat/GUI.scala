package herochat

import scala.collection.JavaConverters._

import akka.actor.{ActorRef}

import scalafx.Includes._
import scalafx.application.JFXApp
import scalafx.application.JFXApp.PrimaryStage
import scalafx.collections.{ObservableBuffer, ObservableMap}
import scalafx.beans.property.{ObjectProperty, StringProperty}
import scalafx.scene.{Scene}
import scalafx.scene.layout.{BorderPane}

import java.util.UUID

import javax.sound.sampled.{Mixer}

import herochat.ui._


case class ChatMessage(sender: Peer, msg: String) {
  override def toString = s"${sender.nickname}: $msg"
}

//UI stuff hopefully
/**
 * TODO: in order to set a OS-global hook for PTT keys, need to use JNI.
 * For now, just use buttons
 */
class HcGUI(localPeer: Peer)(implicit val viewActor: ActorRef) extends JFXApp {
  println(s"gui initialized with view: $viewActor")

  //used to enable/disable certain buttons, modify user settings in options pane
  var localPeerProp = ObjectProperty[Peer](this, "localPeer", localPeer)
  var joinLink = new StringProperty(this, "joinLink")

  var usersInLobby = ObservableBuffer[Peer]()
  var userMap = ObservableMap[UUID, Peer]()
  //var serverList = ObservableBuffer[User]()

  var messages = ObservableBuffer[ChatMessage]()

  val defaultScene = new BorderPane {
    top = new TitlePane().content
    left = new LobbyPane(userMap, localPeerProp).content
    center = new ChatPane(messages).content
    //right = new ServerPane(serverList).content
    bottom = new TestButtonPane(localPeerProp, joinLink).content
  }

  val optionsScene = new OptionsPane(localPeerProp)

  val primaryScene = new Scene {
    stylesheets += getClass.getResource("styles.css").toExternalForm
    root = defaultScene
  }

  stage = new PrimaryStage {
    title = "Herochat (Test)"
    width = 1100
    height = 700
    scene = primaryScene
  }

  def showOptions(): Unit = {
    primaryScene.root = optionsScene
  }

  def showDefault(): Unit = {
    primaryScene.root = defaultScene
  }


  def updateOptionsInputMixers(currentMixer: Mixer.Info, mixers: Array[Mixer.Info]): Unit = {
    optionsScene.audioTab.inMixers.clear()
    optionsScene.audioTab.inMixers ++= mixers
    optionsScene.audioTab.selectedInMixer.update(currentMixer)
  }
  def updateOptionsOutputMixers(currentMixer: Mixer.Info, mixers: Array[Mixer.Info]): Unit = {
    optionsScene.audioTab.outMixers.clear()
    optionsScene.audioTab.outMixers ++= mixers
    optionsScene.audioTab.selectedOutMixer.update(currentMixer)
  }

  viewActor ! HcView.GuiInitialized
}
