package io.nats.client.impl

import cats.effect.implicits.monadCancelOps_
import cats.effect.std.Dispatcher
import cats.effect.{Async, Resource}
import cats.syntax.all.*
import com.evolution.natseffect.impl.{CEMessageHandler, CapturingMessageHandler, ConfiguringMessageHandler}
import io.nats.client.MessageHandler

/* Creates a version of the NatsDispatcher that:
 * - removes the blocking queue used by the default dispatcher
 * - imitates the queue behavior by tracking the number of pending messages and bytes
 * - accepts new messages via push performed by the reader thread
 * - immediately dispatches the message to the handler using a CE dispatcher (either default, or per-subscription)
 * - accepts parameters via ConfiguringMessageHandler (crutch due to lack of better options)
 * This way the callbacks are always executed on separate threads managed by CE
 */
object CatsBasedDispatcherFactory {
  def make[F[_]: Async]: Resource[F, DispatcherFactory] =
    for {
      dispatcherForRequests <- Dispatcher.parallel
    } yield new DispatcherFactory {
      override def createDispatcher(conn: NatsConnection, handlerForDispatcher: MessageHandler): NatsDispatcher =
        new NatsDispatcher(conn, handlerForDispatcher) {

          private val defaultCeDispatcher: Dispatcher[F] = handlerForDispatcher match {
            case ConfiguringMessageHandler(Some(customCeDispatcher), _, _) => customCeDispatcher.asInstanceOf[Dispatcher[F]]
            case _                                                         => dispatcherForRequests
          }

          private val capturingHandler: Option[CapturingMessageHandler[F]] = handlerForDispatcher match {
            case conf: ConfiguringMessageHandler[?] => conf.capturingHandler.map(_.asInstanceOf[CapturingMessageHandler[F]])
            case _                                  => None
          }

          private val defaultMessageHandler: MessageHandler = handlerForDispatcher match {
            case conf: ConfiguringMessageHandler[?] => conf.defaultMessageHandler.orNull
            case _                                  => handlerForDispatcher
          }

          private val incomingQueue: ConsumerMessageQueue = new ConsumerMessageQueue {
            override def push(msg: NatsMessage): Unit = {
              val subscription = msg.getNatsSubscription

              if (isActive && subscription != null && subscription.isActive) {
                val handlerForSubscription = nonDefaultHandlerBySid.get(msg.getSID)
                val handler                = if (handlerForSubscription != null) handlerForSubscription else defaultMessageHandler

                if (handler != null) {
                  subscription.incrementDeliveredCount()
                  incrementDeliveredCount()
                  val messageSize = msg.getSizeInBytes
                  length.incrementAndGet()
                  sizeInBytes.addAndGet(messageSize)

                  val (dispatcher, effect) = handler match {
                    case ceHandler: CEMessageHandler[?] =>
                      val fHandler = ceHandler.asInstanceOf[CEMessageHandler[F]]
                      fHandler.dispatcher -> fHandler.onMessageEffect(msg)
                    case other =>
                      val invoked = capturingHandler match {
                        // `other` is an opaque jnats wrapper (e.g. the JetStream handler performing
                        // status handling, repull pacing, ordered-consumer checks) around the
                        // registered capturing handler: invoke the full chain, then run the captured
                        // user effect here so the recoverWith/guarantee below attach to it rather
                        // than to its mere enqueueing - otherwise handler errors are silently
                        // dropped and pending limits are never enforced
                        case Some(capturing) =>
                          Async[F].delay { other.onMessage(msg); capturing.takeCapturedEffect() }.flatMap {
                            case Some(userEffect) => userEffect
                            case None             => Async[F].unit
                          }
                        case None => Async[F].delay(other.onMessage(msg))
                      }
                      defaultCeDispatcher -> invoked
                  }

                  dispatcher.unsafeToFuture {
                    effect
                      .recoverWith {
                        case e: Error     => Async[F].delay(conn.processException(new Exception(e)))
                        case e: Exception => Async[F].delay(conn.processException(e))
                      }
                      .guarantee(
                        Async[F].delay {
                          length.decrementAndGet()
                          sizeInBytes.addAndGet(-messageSize)
                          if (subscription.reachedUnsubLimit()) conn.invalidate(subscription)
                        }
                      )
                  }
                }
              }

              ()
            }

          }

          override def start(id: String): Unit = internalStart(id, false)

          override def getMessageQueue: ConsumerMessageQueue = incomingQueue
        }
    }
}
