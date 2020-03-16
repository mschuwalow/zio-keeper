package zio.membership.hyparview.plumtree

import zio._
import zio.logging.Logging
import zio.stm._
import zio.membership.hyparview.{ PeerService, TRandom }

object PeerState {

  trait Service[A] {
    val eagerPushPeers: STM[Nothing, Set[A]]
    val lazyPushPeers: STM[Nothing, Set[A]]
    def addToEagerPeers(peer: A): STM[Nothing, Unit]
    def moveToLazyPeers(peer: A): STM[Nothing, Option[A]]
    def removePeer(peer: A): STM[Nothing, Unit]
  }

  def eagerPushPeers[A: Tagged]: ZSTM[PeerState[A], Nothing, Set[A]] =
    ZSTM.accessM(_.get.eagerPushPeers)

  def lazyPushPeers[A: Tagged]: ZSTM[PeerState[A], Nothing, Set[A]] =
    ZSTM.accessM(_.get.lazyPushPeers)

  def addToEagerPeers[A: Tagged](peer: A): ZSTM[PeerState[A], Nothing, Unit] =
    ZSTM.accessM(_.get.addToEagerPeers(peer))

  def moveToLazyPeers[A: Tagged](peer: A): ZSTM[PeerState[A], Nothing, Option[A]] =
    ZSTM.accessM(_.get.moveToLazyPeers(peer))

  def removePeer[A: Tagged](peer: A): ZSTM[PeerState[A], Nothing, Unit] =
    ZSTM.accessM(_.get.removePeer(peer))

  def live[A: Tagged](
    initialEagerPeers: Int
  ): ZLayer[TRandom with PeerService[A] with Logging, Nothing, PeerState[A]] =
    ZLayer.fromEffect {
      for {
        activeView <- PeerService.getPeers[A]
        initial    <- TRandom.using(_.selectN(activeView.toList, initialEagerPeers).commit)
        eagerPeers <- TSet.fromIterable(initial).commit
        lazyPeers  <- TSet.empty[A].commit
      } yield new Service[A] {

        override val eagerPushPeers: STM[Nothing, Set[A]] =
          eagerPeers.toList.map(_.toSet)

        override val lazyPushPeers: STM[Nothing, Set[A]] =
          lazyPeers.toList.map(_.toSet)

        override def addToEagerPeers(peer: A): STM[Nothing, Unit] =
          lazyPeers.delete(peer) *> eagerPeers.put(peer)

        override def moveToLazyPeers(peer: A): STM[Nothing, Option[A]] =
          for {
            contains <- eagerPeers.contains(peer)
            result <- if (contains) eagerPeers.delete(peer) *> lazyPeers.put(peer).as(Some(peer))
                     else STM.succeed(None)
          } yield result

        override def removePeer(peer: A): STM[Nothing, Unit] =
          eagerPeers.delete(peer) *> lazyPeers.delete(peer)
      }
    }
}
