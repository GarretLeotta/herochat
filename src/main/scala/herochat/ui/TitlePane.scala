package herochat.ui

import akka.actor.{ActorRef}

import scalafx.Includes._
import scalafx.geometry.Pos
import scalafx.scene.layout.{TilePane, StackPane}
import scalafx.scene.paint.Color
import scalafx.scene.text.{Font, FontWeight, Text}


class TitlePane(implicit val viewActor: ActorRef) {


  val content = new TilePane {
    snapToPixel = false
    children = List(
      new StackPane {
        style = "-fx-background-color: black"
        children = new Text("Hero") {
          font = Font.font(null, FontWeight.Bold, 18)
          fill = Color.White
          alignmentInParent = Pos.CenterRight
        }
      },
      new Text("Chat") {
        font = Font.font(null, FontWeight.Bold, 18)
        alignmentInParent = Pos.CenterLeft
      },
    )
    prefTileHeight = 40
    prefTileWidth <== parent.selectDouble("width") / 2
  }
}
