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
  abstract class PeerStateChange(val peer: Peer)
  case class NewPeer(override val peer: Peer) extends PeerStateChange(peer)
  case class UpdatePeer(override val peer: Peer) extends PeerStateChange(peer)
  case class RemovePeer(override val peer: Peer) extends PeerStateChange(peer)
}
