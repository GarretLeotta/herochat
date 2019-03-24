package herochat

import scala.collection.JavaConverters._

import akka.actor.{ActorRef}

import scalafx.Includes._
import scalafx.application.JFXApp
import scalafx.application.JFXApp.PrimaryStage
import scalafx.collections.{ObservableBuffer, ObservableMap}
import scalafx.beans.property.{ObjectProperty, StringProperty, DoubleProperty}
import scalafx.scene.Scene
import scalafx.scene.image.{Image, ImageView}
import scalafx.scene.input.{MouseButton, MouseEvent, KeyEvent}
import scalafx.scene.layout.{BorderPane, StackPane}
import scalafx.stage.WindowEvent

import java.util.UUID
import java.util.Timer

import javax.sound.sampled.{Mixer}

import herochat.ui._

case class ChatMessage(sender: Peer, msg: String) {
  override def toString = s"${sender.nickname}: $msg"
}

/**
 * TODO: extend slider class, display value of slider floating above knob, in a little label or something
 */
class HcGUI(settings: Settings)(implicit val viewActor: ActorRef) extends JFXApp {
  println(s"gui initialized with view: $viewActor")

  //used to enable/disable certain buttons, modify user settings in options pane
  var localPeerProp = ObjectProperty[Peer](this, "localPeer", settings.userSettings)
  var joinLink = new StringProperty(this, "joinLink")

  var pttShortcut = ObjectProperty[Settings.KeyBinding](this, "pttShortcut", settings.shortcuts.getOrElse("ptt", null))
  var pttDelay = new DoubleProperty(this, "pttDelay", settings.pttDelayInMilliseconds.toMillis)

  var usersInLobby = ObservableBuffer[Peer]()
  var userMap = ObservableMap[UUID, Peer]()
  //var serverList = ObservableBuffer[User]()

  var messages = ObservableBuffer[ChatMessage]()

  val stylesheet = getClass.getResource("styles.css").toExternalForm

  val timer = new Timer()
  override def stopApp(): Unit = {
    timer.cancel()
  }

  val primaryToaster = new Toaster(timer)
  val defaultScenePane = new StackPane {
    children = Seq(new BorderPane {
      top = new TitlePane().content
      left = new LobbyPane(userMap, localPeerProp).content
      center = new ChatPane(messages).content
      //right = new ServerPane(serverList).content
      bottom = new TestButtonPane(stylesheet, localPeerProp, joinLink).content
    }, primaryToaster)
  }

  val optionsScenePane = new OptionsPane(settings, localPeerProp, pttShortcut, pttDelay)

  val primaryScene = new Scene {
    stylesheets += stylesheet
    root = defaultScenePane
  }

  stage = new PrimaryStage {
    title = "Herochat (Dev)"
    icons += new Image(getClass.getClassLoader.getResourceAsStream("images/icon.png"))
    width = 1100
    height = 700
    scene = primaryScene
    /* Stuff that can only happen after window is initialized */
    this.handleEvent(WindowEvent.WindowShown) { event: WindowEvent => {
      optionsScenePane.onStartup()
    }}
  }

  def showOptions(): Unit = {
    primaryScene.root = optionsScenePane
  }

  def showDefault(): Unit = {
    primaryScene.root = optionsScenePane
  }

  def updateOptionsInputMixers(currentMixer: Mixer.Info, mixers: Array[Mixer.Info]): Unit = {
    optionsScenePane.audioTab.inMixers.clear()
    optionsScenePane.audioTab.inMixers ++= mixers
    optionsScenePane.audioTab.selectedInMixer.update(currentMixer)
  }
  def updateOptionsOutputMixers(currentMixer: Mixer.Info, mixers: Array[Mixer.Info]): Unit = {
    optionsScenePane.audioTab.outMixers.clear()
    optionsScenePane.audioTab.outMixers ++= mixers
    optionsScenePane.audioTab.selectedOutMixer.update(currentMixer)
  }

  viewActor ! HcView.GuiInitialized
}
