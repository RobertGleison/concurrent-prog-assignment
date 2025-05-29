package cp.serverPr.volatileImpl

import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._
import org.slf4j.LoggerFactory

object VolatileRoutes {
  private val logger = LoggerFactory.getLogger(getClass)

  val routes: IO[HttpRoutes[IO]] = for {
    state <- VolatileServerState.create()
  } yield HttpRoutes.of[IO] {

    case GET -> Root / "status" =>
      state.getStatusHtml.flatMap(html =>
        Ok(html)
          .map(addCORSHeaders)
          .map(_.withContentType(org.http4s.headers.`Content-Type`(MediaType.text.html)))
      )

    case req @ GET -> Root / "run-process" =>
      val cmdOpt = req.uri.query.params.get("cmd")
      val userIp = req.remoteAddr.getOrElse("unknown")

      logger.debug(s">>> got run-process!")
      logger.debug(s">>> Cmd: ${cmdOpt}")
      logger.debug(s">>> userIP: $userIp")

      cmdOpt match {
        case Some(cmd) =>
          state.submitProcess(cmd, userIp.toString).flatMap(result =>
            Ok(result).map(addCORSHeaders)
          )

        case None =>
          BadRequest("⚠️ Command not provided. Use /run-process?cmd=<your_command>")
            .map(addCORSHeaders)
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