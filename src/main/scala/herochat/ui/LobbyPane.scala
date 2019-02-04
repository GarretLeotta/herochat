package herochat.ui

import scala.collection.mutable.Buffer

import akka.actor.ActorRef

import scalafx.Includes._
import scalafx.beans.property.ObjectProperty
import scalafx.collections.{ObservableBuffer, ObservableMap}
import scalafx.geometry.Pos
import scalafx.scene.control.{ListCell, ListView, ContextMenu, MenuItem, CustomMenuItem, Slider, CheckBox}
import scalafx.scene.input.{MouseButton, MouseEvent}
import scalafx.scene.layout.{VBox, HBox}
import scalafx.scene.text.{Font, FontWeight, Text}
import scalafx.scene.image.{Image, ImageView}

import javafx.event.ActionEvent

import herochat.{User, HcView, Peer}
import herochat.actors.BigBoss
import herochat.SnakeController.ToModel


/* how does lobbyPane know which peer is the local peer? */
class LobbyPane(var pmap: ObservableMap[User, Peer], localUser: User)(implicit val viewActor: ActorRef) {
  val title = new Text("Lobby") {
    font = Font.font(null, FontWeight.Bold, 12)
    alignmentInParent = Pos.Center
  }

  /* TODO: should take into account if user is muted */
  val micIconMap = Map[Boolean, Image](
    false -> new Image(getClass.getClassLoader.getResourceAsStream("images/mic_black.png")),
    true -> new Image(getClass.getClassLoader.getResourceAsStream("images/mic_red.png")),
  )

  var peer_positions = scala.collection.mutable.Map[User, Int]()

  val lobby_list = new VBox {
    children = ObservableBuffer[HBox]()
    prefWidth = 250
    spacing = 10
  }

  pmap.onChange { (buf, change) => change match {
    case ObservableMap.Add(key, added) =>
      //println(s"pmap: added: $key, $added")
      peer_positions(key) = lobby_list.children.size
      lobby_list.children.add(createPeerNode(added))
    case ObservableMap.Remove(key, removed) =>
      //println(s"pmap: removed: $key, $removed")
      val removed_pos = peer_positions(key)
      lobby_list.children.remove(removed_pos)
      peer_positions = peer_positions.map {
        case (user, pos) if pos > removed_pos => (user, pos-1)
        case x => x
      }
    case ObservableMap.Replace(key, added, removed) =>
      //println(s"pmap: replaced: $key, $added, $removed")
      lobby_list.children.set(peer_positions(key), createPeerNode(added))
    case x => println(s"pmap: unknown change sub: $x")
  }}

  /* This will become a problem point. We create a new peer node every time the state changes.
   * So when we change the state with the context menu still up, a fresh context menu is created
   * but not shown.
   * Also, when we change volume, the peer state changes a lot.
   * There is room for a lot of improvement in this system
   */
  def createPeerNode(peerState: Peer): HBox = {
    val contextMenu = createUserContextMenu(peerState)
    new HBox {
      prefHeight = 20
      spacing = 5
      children = List(
        new Text(peerState.toString) {
          onMouseClicked = { me: MouseEvent => me.button match {
            case MouseButton.Secondary =>
              contextMenu.show(this, me.getScreenX(), me.getScreenY());
            case x => ()
          }}
        },
        new ImageView(micIconMap(peerState.speaking)) {
          fitHeight = 20
          preserveRatio = true
          smooth = true
        }
      )
    }
  }

  def messageMenuItem(label: String, msg: Any): MenuItem = {
    new CustomMenuItem {
      hideOnClick = false
      content = new Text(label)
      onAction = _ => viewActor ! msg
    }
  }

  /* this is a pretty strong case for SetMute(boolean), rather than Mute / UnMute */
  /* TODO: utility to set starting state of checkbox */
  def checkBoxMenuItem(label: String, msgFunc: Boolean => Any): MenuItem = {
    /* TODO: checkboxes need to watch peerState.muted, etc */
    new CustomMenuItem {
      hideOnClick = false
      content = new HBox {
        spacing = 5
        children = Seq(
          new Text(label),
          new CheckBox {
            onAction = (event: ActionEvent) => {
              viewActor ! msgFunc(this.selected.value)
            }
          },
        )
      }
    }
  }

  /* Runs whenever a peer is updated */
  def createUserContextMenu(peerState: Peer): ContextMenu = {
    var menuItems = Buffer[MenuItem]()

    menuItems += checkBoxMenuItem("Mute", ((x: Boolean) => ToModel(BigBoss.SetMuteUser(peerState.user, x))))
    if (peerState.user == localUser) {
      menuItems += checkBoxMenuItem("Deafen", ((x: Boolean) => ToModel(BigBoss.SetDeafenUser(peerState.user, x))))
    } else {
      menuItems += checkBoxMenuItem("Block", ((x: Boolean) => ToModel(BigBoss.SetBlockUser(peerState.user, x))))
      menuItems += new CustomMenuItem {
        hideOnClick = false
        content = new Slider(0, 2, peerState.volume) {
          majorTickUnit = .01
          snapToTicks = true
          value.onChange { (obsVal, oldVal, newVal) => {
            //println(s"slider value changed: $newVal")
            viewActor ! ToModel(BigBoss.SetVolumeUser(peerState.user, newVal.doubleValue))
          }}
        }
      }
    }

    menuItems += (
      checkBoxMenuItem("Server Mute (Need Authority Protocol)", ((x: Boolean) => ToModel(BigBoss.SetServerMuteUser(peerState.user, x)))),
      checkBoxMenuItem("Server Deafen (Need Authority Protocol)", ((x: Boolean) => ToModel(BigBoss.SetServerDeafenUser(peerState.user, x)))),
    )
    new ContextMenu(menuItems:_*)
  }




  val content = new VBox {
    children = ObservableBuffer(title, lobby_list)
  }
}
