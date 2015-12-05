/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import akka.stream._
import akka.stream.stage._
import akka.stream.scaladsl._
import akka.stream.actor.ActorSubscriberMessage
import akka.stream.actor.ActorSubscriberMessage._
import akka.stream.actor.ActorPublisherMessage
import akka.stream.actor.ActorPublisherMessage._
import scala.concurrent.forkjoin.ThreadLocalRandom

import java.{ util ⇒ ju }

final class FlattenMerge[T, M](breadth: Int) extends GraphStage[FlowShape[Source[T, M], T]] {
  private val in = Inlet[Source[T, M]]("flatten.in")
  private val out = Outlet[T]("flatten.out")
  override val shape = FlowShape(in, out)

  override def createLogic(attr: Attributes) = new GraphStageLogic(shape) {

    import StreamOfStreams.{ LocalSink, LocalSource }

    var sources = Set.empty[LocalSource[T]]
    def activeSources = sources.size

    private sealed trait Queue {
      def hasData: Boolean
      def enqueue(src: LocalSource[T]): Unit
      def dequeue(): LocalSource[T]
    }

    private final class FixedQueue extends Queue {
      final val Size = 16
      final val Mask = 15

      private val queue = new Array[LocalSource[T]](Size)
      private var head = 0
      private var tail = 0

      def hasData = tail != head
      def enqueue(src: LocalSource[T]): Unit =
        if (tail - head == Size) {
          val queue = new DynamicQueue
          while (hasData) {
            queue.add(dequeue())
          }
          queue.add(src)
          q = queue
        } else {
          queue(tail & Mask) = src
          tail += 1
        }
      def dequeue(): LocalSource[T] = {
        val ret = queue(head & Mask)
        head += 1
        ret
      }
    }

    private final class DynamicQueue extends ju.LinkedList[LocalSource[T]] with Queue {
      def hasData = !isEmpty()
      def enqueue(src: LocalSource[T]): Unit = add(src)
      def dequeue(): LocalSource[T] = remove()
    }

    private var q: Queue = new FixedQueue

    def pushOut(): Unit = {
      val src = q.dequeue()
      push(out, src.elem)
      src.elem = null.asInstanceOf[T]
      if (src.sub != null) src.sub.pull()
      else removeSource(src)
    }

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        val source = grab(in)
        addSource(source)
        if (activeSources < breadth) tryPull(in)
      }
      override def onUpstreamFinish(): Unit = if (activeSources == 0) completeStage()
    })

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        pull(in)
        setHandler(out, outHandler)
      }
    })

    val outHandler = new OutHandler {
      // could be unavailable due to async input having been executed before this notification
      override def onPull(): Unit = if (q.hasData && isAvailable(out)) pushOut()
    }

    def addSource(source: Source[T, M]): Unit = {
      val localSource = new LocalSource[T]()
      sources += localSource
      val sub = source.runWith(new LocalSink(getAsyncCallback[ActorSubscriberMessage] {
        case OnNext(elem) ⇒
          val elemT = elem.asInstanceOf[T]
          if (isAvailable(out)) {
            push(out, elemT)
            localSource.sub.pull()
          } else {
            localSource.elem = elemT
            q.enqueue(localSource)
          }
        case OnComplete ⇒
          localSource.sub = null
          if (localSource.elem == null) removeSource(localSource)
        case OnError(ex) ⇒
          failStage(ex)
      }.invoke))(interpreter.materializer)
      localSource.sub = sub
    }

    def removeSource(src: LocalSource[T]): Unit = {
      val pullSuppressed = activeSources == breadth
      sources -= src
      if (pullSuppressed) tryPull(in)
      if (activeSources == 0 && isClosed(in)) completeStage()
    }

    override def postStop(): Unit = {
      sources.foreach { src ⇒
        if (src.sub != null) src.sub.cancel()
      }
    }
  }
}

/**
 * INTERNAL API
 */
private[fusing] object StreamOfStreams {
  private val RequestOne = Request(1) // No need to frivolously allocate these
  /**
   * INTERNAL API
   */
  final class LocalSinkSubscription[T](sub: ActorPublisherMessage ⇒ Unit) {
    def pull(): Unit = sub(RequestOne)
    def cancel(): Unit = sub(Cancel)
  }

  /**
   * INTERNAL API
   */
  final class LocalSource[T](var sub: LocalSinkSubscription[T] = null, var elem: T = null.asInstanceOf[T])

  /**
   * INTERNAL API
   */
  private[fusing] final class LocalSink[T](notifier: ActorSubscriberMessage ⇒ Unit) extends GraphStageWithMaterializedValue[SinkShape[T], LocalSinkSubscription[T]] {
    private val in = Inlet[T]("LocalSink.in")
    override val shape = SinkShape(in)
    override def createLogicAndMaterializedValue(attr: Attributes) = {
      final class Logic extends GraphStageLogic(shape) {
        setHandler(in, new InHandler {
          override def onPush(): Unit = notifier(OnNext(grab(in)))
          override def onUpstreamFinish(): Unit = notifier(OnComplete)
          override def onUpstreamFailure(ex: Throwable): Unit = notifier(OnError(ex))
        })
        val sub = new LocalSinkSubscription[T](getAsyncCallback[ActorPublisherMessage] {
          case RequestOne ⇒ tryPull(in)
          case Cancel     ⇒ completeStage()
          case _          ⇒ // FIXME Why is this case acceptable?
        }.invoke)
        override def preStart(): Unit = pull(in)
      }
      val logic = new Logic
      logic -> logic.sub
    }
  }
}