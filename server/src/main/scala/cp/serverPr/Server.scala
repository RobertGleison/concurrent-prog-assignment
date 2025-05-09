package cp.serverPr

import cats.effect.IO
import com.comcast.ip4s._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.Logger
import org.slf4j.LoggerFactory


object Server {
  // Logger object, printing to the file logs/logs.txt
  private val logger = LoggerFactory.getLogger(getClass)

  /** Creates a server instance and runs it, using the routes in `Routes.scala`. */
  def run: IO[Nothing] = {
    logger.info("Starting server...")

    Routes.routes.flatMap { httpRoutes =>
      val httpApp =
        Logger.httpApp(logHeaders = true, logBody = false)(httpRoutes.orNotFound)

      EmberServerBuilder.default[IO]
        .withHost(ipv4"127.0.0.1")
        // TODO: you can change the port number here
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