package cp.serverPr.raceConditionImpl

import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._
import org.slf4j.LoggerFactory

object RaceConditionRoutes {
  private val logger = LoggerFactory.getLogger(getClass)

  // Single instance - NOT thread safe initialization
  @volatile private var sharedState: RaceConditionServerState = null

  // RACE CONDITION 1: Non-atomic lazy initialization
  private def getSharedState: IO[RaceConditionServerState] = IO {
    if (sharedState == null) {
      // Multiple threads can pass this check simultaneously!
      Thread.sleep(1) // Artificial delay to increase race window
      sharedState = new RaceConditionServerState()
      logger.warn(s"⚠️ Created new state instance: ${sharedState.hashCode()}")
    }
    sharedState
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