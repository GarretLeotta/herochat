package herochat.actors


import akka.actor.{Actor, ActorLogging}

import java.util.UUID

import herochat.{Settings}

object PeerPool {

}

/* Lobby / PeerPool, all peers under this actor are connected to eachother.
 * Manages PEX, funnels messages up to bigboss
 * Don't think this will work, one peer might be in multiple peer pools
 */
class PeerPool(
    val id: UUID,
    val settings: Settings,
    settingsFilename: Option[String],
  ) extends Actor with ActorLogging {


  def receive: Receive = {
    case x => x
  }
}
