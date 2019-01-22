package herochat.ui

import scalafx.Includes._
import scalafx.collections.ObservableBuffer
import scalafx.geometry.{Pos, Insets}
import scalafx.scene.control._
import scalafx.scene.control.ButtonBar.ButtonData
import scalafx.scene.layout.{GridPane, VBox}

import javafx.event.ActionEvent


/* TODO: customize more, make OK -> Join, add promptText (hint text / grey text) */
class ConnectionDialog() extends TextInputDialog {
  title = "Connect to a New Server"
  headerText = "Enter a URL, or an IP + Port"
}
