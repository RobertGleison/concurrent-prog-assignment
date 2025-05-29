package cp.serverPr

import cats.effect.IO
import com.comcast.ip4s._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.Logger
import org.slf4j.LoggerFactory
import scala.io.StdIn
import cp.serverPr.serverStates.AtomicLockFreeServerState
import cp.serverPr.serverStates.NonThreadSafeServerState
import cp.serverPr.serverStates.SynchronizedServerState

object Server {
  private val logger = LoggerFactory.getLogger(getClass)

  def run: IO[Nothing] = {
    logger.info("Starting server...")

    // Interactive menu for choosing implementation
    val choice = IO.blocking {
      print("\u001b[2J\u001b[H") // clean terminal
      println("\n" + "="*60)
      println("CONCURRENT SERVER")
      println("="*60)
      println("Choose a concurrency implementation:\n")
      println("1. Synchronized Blocks")
      println("2. Lock-Free Programming")
      println("4. Race Condition Demo")
      println("="*60)
      println("Enter your choice (1-3): ")
      System.out.flush()
      val input = StdIn.readLine()
      input.trim
    }

    choice.flatMap { selection =>
      val (serverState, implementationName) = selection match {
        case "1" =>
          (new SynchronizedServerState(), "Synchronized Blocks")
        case "2" =>
          (new AtomicLockFreeServerState(), "Lock-Free Programming")
        case "3" =>
          (new NonThreadSafeServerState(), "Race Condition Demo")
        case _ =>
          logger.warn(s"Invalid choice: $selection, defaulting to Synchronized")
          println(s"Invalid choice '$selection', using Synchronized implementation")
          (new SynchronizedServerState(), "Synchronized Blocks (Default)")
      }

      logger.info(s"Starting server with implementation: $implementationName")
      if (selection == "3") {
        logger.info("This implementation shows race conditions")
      }

      val routesIO = Routes.createRoutes(serverState)

      routesIO.flatMap { httpRoutes =>
        val httpApp = Logger.httpApp(logHeaders = true, logBody = false)(httpRoutes.orNotFound)

        EmberServerBuilder.default[IO]
          .withHost(ipv4"127.0.0.1")
          .withPort(port"8080")
          .withHttpApp(httpApp)
          .build
          .useForever
          .onError { e =>
            IO(logger.error("Error: server couldn't start.", e))
          }}}}}