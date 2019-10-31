package zio.keeper

import java.util.concurrent.TimeUnit

import zio.clock.Clock
import zio.console.{ Console, putStrLn }
import zio.duration._
import zio.keeper.Cluster.{ Discovery, Transport, readMessage, serializeMessage }
import zio.keeper.Error.{ ConnectionTimeout, HandshakeError, NodeUnknown, SendError }
import zio.keeper.InternalProtocol._
import zio.nio.channels.AsynchronousSocketChannel
import zio.nio.{ InetAddress, SocketAddress }
import zio.random.nextInt
import zio.stream.Stream
import zio.{ Chunk, IO, Ref, ZIO, ZSchedule }
import zio.ZManaged
import zio.UIO

final class InternalCluster(
  localMember: Member,
  nodesRef: Ref[Map[NodeId, AsynchronousSocketChannel]],
  gossipStateRef: Ref[GossipState],
  clusterMessageQueue: zio.Queue[Message],
  userMessageQueue: zio.Queue[Message]
) extends Cluster {

  private def removeMember(member: Member) =
    gossipStateRef.update(_.removeMember(member)) *> // this should be extracted to method
      nodesRef.update(_ - member.nodeId) *>
      putStrLn("remove member: " + member)

  private def startGossiping = {
    for {
      gossipState <- gossipStateRef.get
      _ <- if (gossipState.members.size > 1) {
            val gossipStateWithoutLocal = gossipState.removeMember(localMember)
            for {
              randomInt <- nextInt(gossipStateWithoutLocal.members.size)
              member    = gossipStateWithoutLocal.members.toList(randomInt)
              channel   <- nodesRef.get.map(_.apply(member.nodeId))
              payload   <- ProvideClusterState(gossipState).serialize
              bytes     <- serializeMessage(localMember, payload, 1)
              _ <- channel
                    .write(bytes)
                    .catchAll(_ => removeMember(member))
            } yield ()
          } else {
            putStrLn("no other nodes")
          }
    } yield ()
  }.repeat(ZSchedule.fixed(5.seconds)).fork

  private def listenForClusterMessages =
    ZManaged.finalizerRef(_ => UIO.unit).flatMap { finalizer =>
      val loop =
        for {
          message <- clusterMessageQueue.take
          payload <- InternalProtocol.deserialize(message.payload)
          _ <- payload match {
            case InternalProtocol.NotifyJoin(inetSocketAddress) =>
              for {
                transport <- ZIO.environment[Transport]
                client <- ZIO.uninterruptibleMask { restore =>
                            for {
                              reservation <- transport.connect(inetSocketAddress).reserve
                              _ <- finalizer.update(f => e => f(e) *> reservation.release(e))
                              channel <- restore(reservation.acquire)
                            } yield channel
                          } <*
                          putStrLn(
                            "connected with node [" + message.sender + "] " + inetSocketAddress.hostString + ":" + inetSocketAddress.port
                          ).orDie
                _ <- nodesRef.update(_.updated(message.sender, client)) //TODO here we should propagate membership event
                _ <- gossipStateRef.update(_.addMember(Member(message.sender, inetSocketAddress)))
                _ <- Ack.serialize >>=
                      (serializeMessage(localMember, _: Chunk[Byte], 1)) >>=
                      message.replyTo.write
              } yield ()
            case RequestClusterState =>
              for {
                currentClusterState <- gossipStateRef.get
                payload             <- ProvideClusterState(currentClusterState).serialize
                bytes               <- serializeMessage(localMember, payload, 1)
                _                   <- message.replyTo.write(bytes)
              } yield ()
            case ProvideClusterState(state) =>
              for {
                currentClusterState <- gossipStateRef.get
                diff                = currentClusterState.diff(state)
                _                   = ZIO.foreach_(diff.local)(member => removeMember(member)) //this is incomplete - remote part of diif should be handle

              } yield ()
            case _ => putStrLn("unknown message: " + payload)
          }
        } yield ()
      loop.forever.toManaged_
    }.fork

  private def notifyJoin =
    for {
      payload <- InternalProtocol.NotifyJoin(localMember.addr).serialize
      bytes   <- serializeMessage(localMember, payload, 1)
      nodes   <- nodesRef.get

      _ <- ZIO.traversePar_(nodes) {
            case (node, channel) =>
              channel
                .write(bytes)
                .catchAll(ex => putStrLn("fail to send join notification: " + ex)) *>
                readMessage(channel).either
                  .flatMap {
                    case Right((1, msg)) =>
                      InternalProtocol.deserialize(msg.payload).flatMap {
                        case InternalProtocol.Ack => putStrLn("connected successfully with " + node)
                        case _ =>
                          putStrLn("unexpected response") *>
                            this.nodesRef.update(_ - node)
                      }
                    case Left(ex) =>
                      putStrLn("fail to send join notification: " + ex) *>
                        this.nodesRef.update(_ - node)
                    case Right((_, _)) =>
                      putStrLn("unexpected response") *>
                        this.nodesRef.update(_ - node)

                  }
                  .catchAll(ex => putStrLn("cannot process response for cluster join: " + ex))

          }
    } yield ()

  private def accept =
    for {
      transport <- ZManaged.environment[Transport]
      server    <- transport.bind(localMember.addr).orDie
      _ <- {
        val loop =
          server.accept.use { channel =>
            for {
              typeAndMessage <- readMessage(channel)
              _ <- if (typeAndMessage._1 == 1) {
                    clusterMessageQueue.offer(typeAndMessage._2).unit
                  } else if (typeAndMessage._1 == 2) {
                    userMessageQueue.offer(typeAndMessage._2).unit
                  } else {
                    //this should be dead letter
                    putStrLn("unsupported message type")
                  }
            } yield ()
          }
          .catchAll { ex =>
            putStrLn("channel close because of: " + ex.toString)
          }
          .fork
        loop
          .orDie
          .forever
          .fork
      }.toManaged_
    } yield ()

  override def nodes =
    nodesRef.get
      .map(_.keys.toList)

  override def send(data: Chunk[Byte], receipt: NodeId): IO[Error, Unit] =
    for {
      nodes          <- nodesRef.get
      receiptChannel <- ZIO.fromEither(nodes.get(receipt).toRight(NodeUnknown(receipt)))
      payload        <- serializeMessage(localMember, data, 2)
      _ <- receiptChannel
            .write(payload)
            .catchAll(ex => ZIO.fail(SendError(receipt, data, ex.getMessage)))
    } yield ()

  override def broadcast(data: Chunk[Byte]): IO[Error, Unit] =
    for {
      nodes   <- nodesRef.get
      payload <- serializeMessage(localMember, data, 2).orDie
      _ <- ZIO.traversePar_(nodes) {
            case (receipt, channel) =>
              channel
                .write(payload)
                .catchAll(ex => ZIO.fail(SendError(receipt, data, ex.getMessage)))
          }
    } yield ()

  override def receive: Stream[Error, Message] =
    zio.stream.Stream.fromQueue(userMessageQueue)

}

object InternalCluster {

  private[keeper] def initCluster(port: Int) =
    for {
      localHost <- InetAddress.localHost.toManaged_.orDie
      socketAddress <- SocketAddress
                        .inetSocketAddress(localHost, port)
                        .toManaged_
                        .orDie
      localMember = Member(NodeId.generateNew, socketAddress)
      nodes       <- zio.Ref.make(Map.empty[NodeId, AsynchronousSocketChannel]).toManaged_
      seeds       <- ZManaged.environment[Discovery with Console].flatMap(d => ZManaged.fromEffect(d.discover))
      _           <- putStrLn("seeds: " + seeds).toManaged_
      newState <- ZManaged.collectAll(
        seeds.map(ip => connectToSeed(localMember, ip).foldM(
          ex => putStrLn("seed [" + ip + "] ignored because of: " + ex.getMessage).toManaged_.as(None),
          s => ZManaged.succeed(Some(s))
        ))
      ).map(_.flatten.foldLeft(GossipState.Empty)(_ merge _))
      _ <- ZManaged
            .foreach(newState.members) { m =>
              for {
                transport <- ZManaged.environment[Transport]
                _ <- putStrLn(s"connecting to: $m").toManaged_
                out <- transport.connect(m.addr)
                      .timeout(Duration(10, TimeUnit.SECONDS))
                      .flatMap(_.fold[ZManaged[Clock, Throwable, AsynchronousSocketChannel]](ZManaged.fail(ConnectionTimeout(m.addr)))(ZManaged.succeed))
                _ <- putStrLn(s"connected to: ${m.addr}").toManaged_
              } yield out
            }
            .orDie
      clusterMessagesQueue <- zio.Queue.bounded[Message](1000).toManaged_
      userMessagesQueue    <- zio.Queue.bounded[Message](1000).toManaged_
      gossipState          <- Ref.make(GossipState(Set(localMember)).merge(newState)).toManaged_

      cluster = new InternalCluster(
        localMember,
        nodes,
        gossipState,
        clusterMessagesQueue,
        userMessagesQueue
      )
      _ <- cluster.accept
      _ <- cluster.notifyJoin.toManaged_
      _ <- cluster.listenForClusterMessages
      _ <- cluster.startGossiping.toManaged_
    } yield cluster

  private def connectToSeed(me: Member, seed: SocketAddress) =
    for {
      transport <- ZManaged.environment[Transport with Clock with zio.console.Console]
      _ <-  putStrLn("connecting to: " + seed).toManaged_
      channel <- transport
        .connect(seed)
        .timeout(Duration(10, TimeUnit.SECONDS))
        .flatMap(_.fold[ZManaged[Transport, Throwable, AsynchronousSocketChannel]](
          ZManaged.fail(ConnectionTimeout(seed)))(
          ZManaged.succeed
        ))
      _ <- putStrLn("connected to: " + seed).toManaged_
      //initial handshake
      _     <- putStrLn("starting handshake").toManaged_
      bytes <- RequestClusterState.serialize.flatMap(serializeMessage(me, _, 1)).toManaged_
      _     <- channel.write(bytes).toManaged_
      msg   <- readMessage(channel).toManaged_

      m <- InternalProtocol.deserialize(msg._2.payload).toManaged_
      res <- m match {
              case InternalProtocol.ProvideClusterState(gossipState) =>
                ZManaged.succeed(gossipState) <* putStrLn("retrieved state from seed: " + seed).toManaged_
              case _ =>
                ZManaged.fail(HandshakeError("handshake error for " + seed))
            }
    } yield res
}
