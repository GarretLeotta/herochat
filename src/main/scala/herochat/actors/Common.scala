package herochat.actors

import akka.actor.{ActorRef}

import scodec.bits.ByteVector

//Common messages for actors in project
case class AddSubscriber(sub: ActorRef)
case class RemoveSubscriber(sub: ActorRef)

/* This is sketchy
 * TODO: test that order actually works
 * TODO: read a book on type theory so I understand what [-T,+R] means
 * NOTE: below line didn't work, why not? why did stack overflow guy tell me to use [-T,+R] anyway?
 * class OrderedPartialFunction[T,R](val order: Int, underlying: PartialFunction[T,R]) extends PartialFunction[T,R] with Ordered[OrderedPartialFunction[T,R]] {
 *
 * TODO: document this
 * TODO: also, this doesn't belong in actors package
 */
class OrderedPartialFunction[T,R](val order: Int, val underlying: PartialFunction[T,R]) extends PartialFunction[T,R] with Ordered[OrderedPartialFunction[T,R]] {
  def compare(that: OrderedPartialFunction[T,R]) = {
      order.compare(that.order) match {
        case 0 => underlying.hashCode.compare(that.underlying.hashCode)
        case x => x
      }
  }
  def apply(t: T) = underlying.apply(t)
  def isDefinedAt(t: T) = underlying.isDefinedAt(t)
}
