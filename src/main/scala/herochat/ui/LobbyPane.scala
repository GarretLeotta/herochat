package herochat.ui

import scala.collection.mutable.Buffer

import akka.actor.ActorRef

import scalafx.Includes._
import scalafx.beans.property.ObjectProperty
import scalafx.collections.{ObservableBuffer, ObservableMap}
import scalafx.geometry.Pos
import scalafx.scene.control.{Label, ListCell, ListView, ContextMenu, MenuItem, CustomMenuItem, Slider, CheckBox}
import scalafx.scene.input.{MouseButton, MouseEvent}
import scalafx.scene.layout.{VBox, HBox}
import scalafx.scene.text.{Font, FontWeight, Text}
import scalafx.scene.image.{Image, ImageView}

import javafx.event.ActionEvent
import java.util.UUID

import herochat.{HcView, Peer}
import herochat.actors.BigBoss
import herochat.SnakeController.ToModel


/* how does lobbyPane know which peer is the local peer? */
class LobbyPane(
    val pmap: ObservableMap[UUID, Peer],
    val localPeer: ObjectProperty[Peer]
  )(implicit val viewActor: ActorRef) extends VBox {
  val title = new Label("Lobby") {
    font = Font.font(null, FontWeight.Bold, 12)
    alignmentInParent = Pos.Center
  }

  /* TODO: should take into account if user is muted */
  val micIconMap = Map[Boolean, Image](
    false -> new Image(getClass.getClassLoader.getResourceAsStream("images/mic_black.png")),
    true -> new Image(getClass.getClassLoader.getResourceAsStream("images/mic_red.png")),
  )

  /* TODO: double mutability */
  var peer_positions = scala.collection.mutable.Map[UUID, Int]()

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

  def customPeerString(peerState: Peer): String = {
    peerState.nickname + "; " +
    peerState.muted + "; " +
    peerState.deafened + "; " +
    f"${peerState.volume}%1.2f"
  }

  /* This will become a problem point. We create a new peer node every time the state changes.
   * So when we change the state with the context menu still up, a fresh context menu is created
   * but not shown.
   * Also, when we change volume, the peer state changes a lot.
   * There is room for a lot of improvement in this system
   */
  def createPeerNode(peerState: Peer): HBox = {
    //val contextMenu = createUserContextMenu(peerState)
    new HBox {
      prefHeight = 20
      spacing = 5
      children = List(
        new Label(customPeerString(peerState)) {
          contextMenu = createUserContextMenu(peerState)
          /*
          onMouseClicked = { me: MouseEvent => me.button match {
            case MouseButton.Secondary =>
              contextMenu.show(this, me.getScreenX(), me.getScreenY())
            case x => ()
          }}
          */
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
      content = new Label(label)
      onAction = _ => viewActor ! msg
    }
  }

  /* this is a pretty strong case for SetMute(boolean), rather than Mute / UnMute */
  /* TODO: would properties for every peer be better than current system? */
  /* I think this works now, because we run this every time peerState changes */
  def checkBoxMenuItem(label: String, startValue: Boolean, msgFunc: Boolean => Any): MenuItem = {
    new CustomMenuItem {
      hideOnClick = false
      content = new HBox {
        spacing = 5
        children = Seq(
          new Label(label),
          new CheckBox {
            this.selected = startValue
            onAction = (event: ActionEvent) => {
              viewActor ! msgFunc(this.selected())
            }
          },
        )
      }
    }
  }

  /* Runs whenever a peer is updated */
  def createUserContextMenu(peerState: Peer): ContextMenu = {
    val menuItems = Buffer[MenuItem]()

    menuItems += checkBoxMenuItem("Mute", peerState.muted, ((x: Boolean) => ToModel(BigBoss.SetMuteUser(peerState.id, x))))
    if (peerState.id == localPeer().id) {
      menuItems += checkBoxMenuItem("Deafen", peerState.deafened, ((x: Boolean) => ToModel(BigBoss.SetDeafenUser(peerState.id, x))))
    } else {
      /* TODO: add block */
      //menuItems += checkBoxMenuItem("Block", ((x: Boolean) => ToModel(BigBoss.SetBlockUser(peerState.user, x))))
      menuItems += new CustomMenuItem {
        hideOnClick = false
        content = new Slider(0, 2, peerState.volume) {
          majorTickUnit = .01
          snapToTicks = true
          value.onChange { (obsVal, oldVal, newVal) => {
            viewActor ! ToModel(BigBoss.SetVolumeUser(peerState.id, newVal.doubleValue))
          }}
        }
      }
    }
    /** TODO: add when authority protocol exists
    menuItems += (
      checkBoxMenuItem("Server Mute (Need Authority Protocol)", ((x: Boolean) => ToModel(BigBoss.SetServerMuteUser(peerState.user, x)))),
      checkBoxMenuItem("Server Deafen (Need Authority Protocol)", ((x: Boolean) => ToModel(BigBoss.SetServerDeafenUser(peerState.user, x)))),
    )
    */
    new ContextMenu(menuItems:_*)
  }

  children = ObservableBuffer(title, lobby_list)
}
