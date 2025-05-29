package cp.serverPr

import cats.effect.IO
import com.comcast.ip4s._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.Logger
import org.slf4j.LoggerFactory
import scala.io.StdIn

import cp.serverPr.synchronizedImpl.SynchronizedRoutes
import cp.serverPr.lockFreeImpl.LockFreeRoutes
import cp.serverPr.volatileImpl.VolatileRoutes
import cp.serverPr.raceConditionImpl.RaceConditionRoutes


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
      println("3. Volatile Variables")
      println("4. Race Condition Demo")
      println("="*60)
      println("Enter your choice (1-4): ")
      System.out.flush()

      val input = StdIn.readLine()
      input.trim
    }

    choice.flatMap { selection =>
      val routesIO = selection match {
        case "1" =>
          logger.info(s"Starting server with implementation: Synchronized Blocks")
          SynchronizedRoutes.routes
        case "2" =>
          logger.info(s"Starting server with implementation: Lock-Free Programming")
          LockFreeRoutes.routes
        case "3" =>
          logger.info(s"Starting server with implementation: Volatile Variables")
          VolatileRoutes.routes
        case "4" =>
          logger.info(s"Starting server with implementation: Race Condition Demo")
          logger.info(s"This implementation shows race condidions")
          RaceConditionRoutes.routes
        case _ =>
          logger.warn(s"Invalid choice: $selection, defaulting to Synchronized")
          println(s"Invalid choice '$selection', using Synchronized implementation")
          SynchronizedRoutes.routes
      }

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
          }
      }
    }
  }
}