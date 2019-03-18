package herochat.ui

import scalafx.Includes._
import scalafx.scene.control.TextInputDialog

import javafx.event.ActionEvent


/* TODO: customize more, make OK -> Join, add promptText (hint text / grey text) */
class ConnectionDialog(stylesheet: String) extends TextInputDialog {
  dialogPane().stylesheets += stylesheet
  title = "Connect to a New Server"
  headerText = "Enter a URL, or an IP + Port"
}
