package herochat


import java.util.UUID


/**
 * becoming a problem, id never changes, but nickname can change. Makes using this as a key annoying
 */
case class User(val id: UUID, val nickname: String)

/**
 * Major question: is this class for display, or more general
 *   It's becoming more useful for display
 * Should I expose the remote IP address in this class?
 *
 * this shouldn't be a case class
 * TODO: too many representations of "peers", PeerTable has two different tuples, plus this class
 *   what is the difference between all these
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
