package zio.keeper.hyparview

import zio.keeper.NodeAddress
import zio.stm._
import zio.stream.{ Stream, ZStream }
import zio._
import zio.keeper.hyparview.ViewEvent._

object Views {

  trait Service {
    def activeView: USTM[Set[NodeAddress]]
    def activeViewCapacity: USTM[Int]
    def addAllToPassiveView(nodes: List[NodeAddress]): USTM[Unit]
    def addToActiveView(node: NodeAddress, send: Message => USTM[_], disconnect: USTM[_]): USTM[Unit]
    def addToPassiveView(node: NodeAddress): USTM[Unit]
    def events: Stream[Nothing, ViewEvent]
    def myself: USTM[NodeAddress]
    def passiveView: USTM[Set[NodeAddress]]
    def passiveViewCapacity: USTM[Int]
    def removeFromActiveView(node: NodeAddress): USTM[Unit]
    def removeFromPassiveView(node: NodeAddress): USTM[Unit]
    def send(to: NodeAddress, msg: Message): USTM[Unit]
  }

  def activeView: ZSTM[Views, Nothing, Set[NodeAddress]] =
    ZSTM.accessM(_.get.activeView)

  def activeViewCapacity: ZSTM[Views, Nothing, Int] =
    ZSTM.accessM(_.get.activeViewCapacity)

  def activeViewSize: ZSTM[Views, Nothing, Int] =
    ZSTM.accessM(_.get.activeView.map(_.size))

  def addAllToPassiveView(nodes: List[NodeAddress]): ZSTM[Views, Nothing, Unit] =
    ZSTM.accessM(_.get.addAllToPassiveView(nodes))

  def addToActiveView(node: NodeAddress, send: Message => USTM[_], disconnect: USTM[_]): ZSTM[Views, Nothing, Unit] =
    ZSTM.accessM(_.get.addToActiveView(node, send, disconnect))

  def addToPassiveView(node: NodeAddress): ZSTM[Views, Nothing, Unit] =
    ZSTM.accessM(_.get.addToPassiveView(node))

  def events: ZStream[Views, Nothing, ViewEvent] =
    ZStream.accessStream(_.get.events)

  def isActiveViewFull: ZSTM[Views, Nothing, Boolean] =
    activeViewSize.zipWith(activeViewCapacity)(_ >= _)

  def isPassiveViewFull: ZSTM[Views, Nothing, Boolean] =
    passiveViewSize.zipWith(passiveViewCapacity)(_ >= _)

  def myself: ZSTM[Views, Nothing, NodeAddress] =
    ZSTM.accessM(_.get.myself)

  def passiveView: ZSTM[Views, Nothing, Set[NodeAddress]] =
    ZSTM.accessM(_.get.passiveView)

  def passiveViewCapacity: ZSTM[Views, Nothing, Int] =
    ZSTM.accessM(_.get.passiveViewCapacity)

  def passiveViewSize: ZSTM[Views, Nothing, Int] =
    ZSTM.accessM(_.get.passiveView.map(_.size))

  def removeFromActiveView(node: NodeAddress): ZSTM[Views, Nothing, Unit] =
    ZSTM.accessM(_.get.removeFromActiveView(node))

  def removeFromPassiveView(node: NodeAddress): ZSTM[Views, Nothing, Unit] =
    ZSTM.accessM(_.get.removeFromPassiveView(node))

  def send(to: NodeAddress, msg: Message): ZSTM[Views, Nothing, Unit] =
    ZSTM.accessM(_.get.send(to, msg))

  def viewState: ZSTM[Views, Nothing, ViewState] =
    for {
      activeViewSize      <- activeViewSize
      activeViewCapacity  <- activeViewCapacity
      passiveViewSize     <- passiveViewSize
      passiveViewCapacity <- passiveViewCapacity
    } yield ViewState(activeViewSize, activeViewCapacity, passiveViewSize, passiveViewCapacity)

  def live: ZLayer[TRandom with HyParViewConfig, Nothing, Views] =
    ZLayer.fromEffect {
      for {
        config           <- HyParViewConfig.getConfig
        _                <- ZIO.dieMessage("active view capacity must be greater than 0").when(config.activeViewCapacity <= 0)
        _                <- ZIO.dieMessage("passive view capacity must be greater than 0").when(config.passiveViewCapacity <= 0)
        viewEvents       <- TQueue.bounded[ViewEvent](config.viewsEventBuffer).commit
        activeViewState  <- TMap.empty[NodeAddress, (Message => USTM[Any], USTM[Any])].commit
        passiveViewState <- TSet.empty[NodeAddress].commit
        tRandom          <- URIO.environment[TRandom].map(_.get)
      } yield new Service {

        override val activeView: USTM[Set[NodeAddress]] =
          activeViewState.keys.map(_.toSet)

        override val activeViewCapacity: USTM[Int] =
          STM.succeedNow(config.activeViewCapacity)

        override def addAllToPassiveView(remaining: List[NodeAddress]): USTM[Unit] =
          remaining match {
            case Nil     => STM.unit
            case x :: xs => addToPassiveView(x) *> addAllToPassiveView(xs)
          }

        override def addToActiveView(node: NodeAddress, send: Message => USTM[_], disconnect: USTM[_]): USTM[Unit] =
          STM.unless(node == config.address) {
            ZSTM.ifM(activeViewState.contains(node))(
              {
                for {
                  _ <- removeFromActiveView(node)
                  _ <- addToActiveView(node, send, disconnect)
                } yield ()
              }, {
                for {
                  _ <- {
                    for {
                      activeView <- activeView
                      node       <- tRandom.selectOne(activeView.toList)
                      _          <- node.fold[USTM[Unit]](STM.unit)(removeFromActiveView)
                    } yield ()
                  }.whenM(activeViewState.size.map(_ >= config.activeViewCapacity))
                  _ <- viewEvents.offer(AddedToActiveView(node))
                  _ <- activeViewState.put(node, (send, disconnect))
                } yield ()
              }
            )
          }

        override def addToPassiveView(node: NodeAddress): USTM[Unit] =
          for {
            inActive            <- activeViewState.contains(node)
            inPassive           <- passiveViewState.contains(node)
            passiveViewCapacity <- passiveViewCapacity
            _ <- if (node == config.address || inActive || inPassive) STM.unit
                else {
                  for {
                    size <- passiveViewState.size
                    _    <- dropOneFromPassive.when(size >= passiveViewCapacity)
                    _    <- viewEvents.offer(AddedToPassiveView(node))
                    _    <- passiveViewState.put(node)
                  } yield ()
                }
          } yield ()

        override val events: Stream[Nothing, ViewEvent] =
          Stream.fromTQueue(viewEvents)

        override val myself: USTM[NodeAddress] =
          STM.succeed(config.address)

        override val passiveView: USTM[Set[NodeAddress]] =
          passiveViewState.toList.map(_.toSet)

        override val passiveViewCapacity: USTM[Int] =
          STM.succeed(config.passiveViewCapacity)

        override def removeFromActiveView(node: NodeAddress): USTM[Unit] =
          activeViewState
            .get(node)
            .get
            .foldM(
              _ => STM.unit, {
                case (_, disconnect) =>
                  for {
                    _ <- viewEvents.offer(RemovedFromActiveView(node))
                    _ <- activeViewState.delete(node)
                    _ <- disconnect
                  } yield ()
              }
            )

        override def removeFromPassiveView(node: NodeAddress): USTM[Unit] = {
          viewEvents.offer(RemovedFromPassiveView(node)) *> passiveViewState.delete(node)
        }.whenM(passiveViewState.contains(node))

        override def send(to: NodeAddress, msg: Message): USTM[Unit] =
          activeViewState
            .get(to)
            .get
            .foldM(
              _ => viewEvents.offer(UnhandledMessage(to, msg)),
              _._1(msg).unit
            )

        private def dropNFromPassive(n: Int): USTM[Unit] =
          (dropOneFromPassive *> dropNFromPassive(n - 1)).when(n > 0)

        private val dropOneFromPassive: USTM[Unit] =
          for {
            list    <- passiveViewState.toList
            dropped <- tRandom.selectOne(list)
            _       <- STM.foreach_(dropped)(removeFromPassiveView(_))
          } yield ()
      }
    }

}
