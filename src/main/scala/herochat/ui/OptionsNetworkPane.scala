package herochat.ui

import akka.actor.{ActorRef}

import scalafx.Includes._
import scalafx.beans.property.{IntegerProperty, ObjectProperty}
import scalafx.collections.ObservableBuffer
import scalafx.geometry.{Pos, HPos, Insets}
import scalafx.scene.input.MouseEvent
import scalafx.scene.layout.{HBox, VBox, GridPane, ColumnConstraints}
import scalafx.scene.control.{Button, Label, ComboBox, ListCell, ListView, TextField}
import scalafx.scene.text.{Font, FontWeight, Text}

import javafx.event.ActionEvent

import java.net.{InetSocketAddress, InetAddress}


import herochat.{HcView, Settings}
import herochat.actors.BigBoss
import herochat.SnakeController.ToModel


class OptionsNetworkPane(settings: Settings)(implicit val viewActor: ActorRef) extends VBox {
  spacing = 10
  padding = Insets(20)

  val localAddresses = ObservableBuffer[InetAddress]()
  val localAddress = new ObjectProperty[InetAddress](this, "localAddress")
  val localPort = new IntegerProperty(this, "localPort")

  /* TODO: create a comboBox[T] */
  def addressComboBox(
      addrs: ObservableBuffer[InetAddress],
      currentAddress: ObjectProperty[InetAddress] ): ComboBox[InetAddress] = {
    val comboBox = new ComboBox[InetAddress] {
      items = addrs
      cellFactory = { p =>
        new ListCell[InetAddress] {
          item.onChange { (obsVal, oldVal, newVal) => {
            Option(newVal) match {
              case Some(address) => text = newVal.toString
              case None => text = null
            }
          }}
        }
      }
      value <==> currentAddress
    }
    comboBox
  }
  def changeAddressMsg(ae: ActionEvent): Unit = {
    println(s"gui change addr: ${Option(localAddress())}, ${Option(localPort())}")
    Option(localAddress()).foreach { addr =>
      Option(localPort()).foreach { port =>
          println(s"sending message, $addr, $port")
          viewActor ! ToModel(BigBoss.SetListenAddress(new InetSocketAddress(addr, port)))
      }
    }
  }

  val addressSelectBox = addressComboBox(localAddresses, localAddress)

  val portForm = new HBox {
    children = Array(
      new TextField {
        //text <== localPort
        text = "Not Implemented"
        editable = false
      },
      new Button("Change Address + Port") {
        onAction = changeAddressMsg
      },
    )
  }

  val networkSettings = new GridPane {
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
    add(new Label("Local IP Address"), 0, 0)
    add(new Label("Local Port"), 1, 0)
    add(addressSelectBox, 0, 1)
    add(portForm, 1, 1)
  }

  children = Array(
    new Label("Network Settings") {
      font = Font.font(null, FontWeight.Bold, 18)
      alignmentInParent = Pos.CenterLeft
    },
    networkSettings,
  )
}
