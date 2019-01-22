package herochat




/**
 * Major question: is this class for display, or more general
 *   It's becoming more useful for display
 * Should I expose the remote IP address in this class?
 *
 * this shouldn't be a case class
 */
case class Peer(
    val user: User,
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
