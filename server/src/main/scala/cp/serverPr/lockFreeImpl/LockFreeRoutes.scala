package cp.serverPr.lockFreeImpl

import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

object LockFreeRoutes {
  private val logger = LoggerFactory.getLogger(getClass)

  // Atomic reference to hold the shared state - thread safe
  private val sharedStateRef = new AtomicReference[LockFreeServerState](null)

  // Simple thread-safe lazy initialization
  private def getSharedState: IO[LockFreeServerState] = {
    Option(sharedStateRef.get()) match {
      case Some(state) => IO.pure(state)
      case None =>
        // Initialize once
        LockFreeServerState.create().map { state =>
          if (sharedStateRef.compareAndSet(null, state)) {
            state
          } else {
            // Another thread initialized it first
            sharedStateRef.get()
          }
        }
    }
  }

  val routes: IO[HttpRoutes[IO]] = IO.pure {
    HttpRoutes.of[IO] {
      case GET -> Root / "status" =>
        for {
          state <- getSharedState
          html <- state.getStatusHtml
          response <- Ok(html)
            .map(addCORSHeaders)
            .map(_.withContentType(org.http4s.headers.`Content-Type`(MediaType.text.html)))
        } yield response

      case req @ GET -> Root / "run-process" =>
        val cmdOpt = req.uri.query.params.get("cmd")
        val userIp = req.remoteAddr.getOrElse("unknown")

        logger.debug(s">>> got run-process!")
        logger.debug(s">>> Cmd: $cmdOpt")
        logger.debug(s">>> userIP: $userIp")

        cmdOpt match {
          case Some(cmd) =>
            for {
              state <- getSharedState
              result <- state.executeCommand(cmd, userIp.toString)
              response <- Ok(result).map(addCORSHeaders)
            } yield response
          case None =>
            BadRequest("⚠️ Command not provided. Use /run-process?cmd=<your_command>")
              .map(addCORSHeaders)
        }
    }
  }

  def addCORSHeaders(response: Response[IO]): Response[IO] = {
    response.putHeaders(
      "Access-Control-Allow-Origin" -> "*",
      "Access-Control-Allow-Methods" -> "GET, POST, PUT, DELETE, OPTIONS",
      "Access-Control-Allow-Headers" -> "Content-Type, Authorization",
      "Access-Control-Allow-Credentials" -> "true"
    )
  }
}
