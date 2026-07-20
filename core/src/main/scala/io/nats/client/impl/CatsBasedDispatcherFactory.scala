package io.nats.client.impl

import cats.effect.implicits.monadCancelOps_
import cats.effect.std.Dispatcher
import cats.effect.{Async, Resource}
import cats.syntax.all.*
import com.evolution.natseffect.impl.{CEMessageHandler, ConfiguringMessageHandler}
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

          private val defaultMessageHandler: MessageHandler = handlerForDispatcher match {
            case conf: ConfiguringMessageHandler[?] => conf.defaultMessageHandler.orNull
            case _                                  => handlerForDispatcher
          }

          private val exclusiveDefaultHandler: Boolean = handlerForDispatcher match {
            case conf: ConfiguringMessageHandler[?] => conf.exclusiveDefaultHandler
            case _                                  => false
          }

          private val incomingQueue: ConsumerMessageQueue = new ConsumerMessageQueue {
            override def push(msg: NatsMessage): Unit = {
              val subscription = msg.getNatsSubscription

              if (isActive && subscription != null && subscription.isActive) {
                // An exclusive default handler takes every message of this dispatcher, bypassing
                // handlers registered per subscription (for JetStream subscriptions that is the
                // jnats wrapper, which would otherwise swallow status messages and hide the raw
                // message behind its own machinery)
                val handler =
                  if (exclusiveDefaultHandler && defaultMessageHandler != null) defaultMessageHandler
                  else {
                    val handlerForSubscription = nonDefaultHandlerBySid.get(msg.getSID)
                    if (handlerForSubscription != null) handlerForSubscription else defaultMessageHandler
                  }

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
                      defaultCeDispatcher -> Async[F].delay(other.onMessage(msg))
                  }

                  def cleanup(): Unit = {
                    length.decrementAndGet()
                    sizeInBytes.addAndGet(-messageSize)
                    if (subscription.reachedUnsubLimit()) conn.invalidate(subscription)
                  }

                  try
                    dispatcher.unsafeToFuture {
                      effect
                        .recoverWith {
                          case e: Error     => Async[F].delay(conn.processException(new Exception(e)))
                          case e: Exception => Async[F].delay(conn.processException(e))
                        }
                        .guarantee(Async[F].delay(cleanup()))
                    }
                  catch {
                    // Pushing new messages happens independently from the Dispatcher lifecycle which causes a race
                    // condition between releasing the dispatcher and processing a message. If Cats Effect Dispatcher
                    // has already been closed, the error can be safely ignored.
                    case _: IllegalStateException => cleanup()
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
