package zio.membership.hyparview

import zio._
import zio.membership.ByteCodec
import zio.membership.ByteCodec._
import zio.membership.transport.Transport
import zio.stm._
import zio.console.Console

object periodic {

  private[hyparview] def doNeighbor[T](
    implicit
    ev1: ByteCodec[Protocol.Disconnect[T]],
    ev2: ByteCodec[Protocol.ForwardJoin[T]],
    ev3: ByteCodec[Protocol.Shuffle[T]],
    ev4: ByteCodec[InitialProtocol.ForwardJoinReply[T]],
    ev5: ByteCodec[InitialProtocol.ShuffleReply[T]]
  ): ZIO[Console with Env[T] with Transport[T], Nothing, Unit] =
    ZIO.environment[Env[T]].map(_.env).flatMap { env =>
      for {
        promoted <- env.promoteRandom.commit
        _ <- ZIO.foreach(promoted) { node =>
              (for {
                con <- ZIO.accessM[Transport[T]](_.transport.connect(node))
                reply <- con.receive.take(1).mapM(decode[NeighborProtocol]).runHead.map(_.get)
                _  <- reply match {
                  case NeighborProtocol.Accept => addConnection(node, con).fork
                  case NeighborProtocol.Reject => con.close *> doNeighbor
                }
              } yield ()) orElse (env.passiveView.delete(node).commit *> doNeighbor)
            }
      } yield ()
    }

  private[hyparview] def doShuffle[T](
    implicit
    ev: ByteCodec[Protocol.Shuffle[T]]
  ) =
    ZIO.environment[Env[T]].map(_.env).flatMap { env =>
      (for {
        nodes   <- env.activeView.keys
        target  <- env.selectOne(nodes)
        task    <- target match {
          case None => STM.succeed(ZIO.unit)
          case Some(node) =>
            for {
              active  <- env.selectN(nodes.filter(_ != node), env.cfg.shuffleNActive)
              passive <- env.passiveView.toList.flatMap(env.selectN(_, env.cfg.shuffleNPassive))
            } yield send(node, Protocol.Shuffle(env.myself, env.myself, active, passive, TimeToLive(env.cfg.shuffleTTL)))
        }
      } yield task).commit.flatten
    }

}
