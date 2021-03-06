package demo

import java.util.concurrent.CountDownLatch

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Terminated}
import akka.util.Timeout

import scala.concurrent.duration._
import scala.util.{Failure, Success}


object Ping {
  sealed trait Request
  private final case class WrappedResponse(response: Pong.Response) extends Request
  def apply(latch: CountDownLatch): Behavior[Request] = Behaviors.setup { context =>
    implicit val timeout: Timeout = 2.seconds
    val pong = context.spawn(Pong(), "pong")
    context.watch(pong)
    context.ask(pong, (replyTo: ActorRef[Pong.Response]) => Pong.Message("Hello Scala!", 1, replyTo)) {
      case Success(value) => WrappedResponse(value)
      case Failure(exception) => throw exception
    }

    Behaviors.receiveMessage[Request] {
      case WrappedResponse(Pong.Result(message, count)) =>
        context.log.info(s"Received pong response: $message, ${count}th.")
        context.ask[Pong.Request, Pong.Response](pong, Pong.Message(message, count+1, _)) {
          case Success(value)     => WrappedResponse(value)
          case Failure(exception) => throw exception
        }
        Behaviors.same
    }
      .receiveSignal {
        case (_, Terminated(`pong`)) =>
          context.log.info(s"Actor $pong be terminated.")
          latch.countDown()
          Behaviors.stopped
      }
  }
}

object Pong {
  sealed trait Request
  final case class Message(message: String, count: Int, replyTo: ActorRef[Response]) extends Request
  sealed trait Response
  final case class Result(message: String, count: Int) extends Response

  def apply(): Behavior[Request] = Behaviors.receive {
    case (context, Message(message, 100, _)) =>
      context.log.info(s"Receiving 100th Ping message: $message, it will stop.")
      Behaviors.stopped
    case (_, Message(message, count, replyTo)) =>
      replyTo ! Result(message, count)
      Behaviors.same
  }
}

object Pingpong {
  def main(args: Array[String]): Unit = {
    val latch = new CountDownLatch(1)
    val system = ActorSystem(Ping(latch), "ping-pong")
    latch.await()
    system.terminate()
  }
}
