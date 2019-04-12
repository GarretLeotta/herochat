package herochat.ui

import akka.actor.{ActorRef}

import scalafx.Includes._
import scalafx.geometry.Pos
import scalafx.scene.layout.{TilePane, StackPane}
import scalafx.scene.paint.Color
import scalafx.scene.control.{Label}
import scalafx.scene.text.{Font, FontWeight, Text}


class TitlePane(implicit val viewActor: ActorRef) extends Label {
  text = "Herochat"
  font = Font.font(null, FontWeight.Bold, 20)
  alignmentInParent = Pos.CenterLeft
}
