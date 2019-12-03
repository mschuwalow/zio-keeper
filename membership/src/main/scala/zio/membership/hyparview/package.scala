package zio.membership

import zio.membership.transport.ChunkConnection
import zio._
import zio.membership.ByteCodec._
import zio.stm._

package object hyparview {

  private[hyparview] type Handler = Fiber[Error, Unit]

  private[hyparview] def addConnection[T](
    to: T,
    con: ChunkConnection
  )(implicit
    ev1: ByteCodec[Protocol.Disconnect[T]],
    ev2: ByteCodec[Protocol.ForwardJoin[T]],
    ev3: ByteCodec[Protocol.Shuffle[T]],
    ev4: ByteCodec[InitialProtocol.ForwardJoinReply[T]],
    ev5: ByteCodec[InitialProtocol.ShuffleReply[T]]
  ) =
  for {
    dropped <- Env.addNodeToActiveView(to, con)
    _       <- ZIO.foreach(dropped)(disconnect(_))
    _       <- Protocol.protocolHandler(con)
  } yield ()

  private[hyparview] def send[T, M <: Protocol[T]: ByteCodec](to: T, msg: M) =
    for {
      chunk <- encode(msg)
      con   <- Env.activeView[T].flatMap(_.get(to).commit)
      _     <- ZIO.foreach(con)(_.send(chunk))
    } yield ()

  private[hyparview] def disconnect[T](
    node: T,
    shutDown: Boolean = false
  )(
    implicit
    ev1: ByteCodec[Protocol.Disconnect[T]]
  ) =
    ZIO.environment[Env[T]].map(_.env).flatMap { env =>
      (for {
        conOpt <- env.activeView.get(node)
        task <- conOpt match {
                 case Some(con) =>
                   for {
                     _ <- env.activeView.delete(node)
                     _ <- env.addNodeToPassiveView(node)
                   } yield for {
                     _ <- encode(Protocol.Disconnect(env.myself, shutDown)).flatMap(con.send).ignore.unit
                     _ <- con.close
                   } yield ()
                 case None => STM.succeed(ZIO.unit)
               }
      } yield task).commit.flatten
    }

}
