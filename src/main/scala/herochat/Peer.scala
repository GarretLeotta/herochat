package herochat


import java.util.UUID

/**
 * 
 */
case class Peer(
    val id: UUID,
    val nickname: String,
    val muted: Boolean,
    val deafened: Boolean,
    val speaking: Boolean,
    val volume: Double,
)



object PeerState {
  abstract class PeerStateChange()
  case class NewPeer(peer: Peer) extends PeerStateChange
  case class UpdatePeer(peer: Peer) extends PeerStateChange
  case class RemovePeer(peer: Peer) extends PeerStateChange
}
