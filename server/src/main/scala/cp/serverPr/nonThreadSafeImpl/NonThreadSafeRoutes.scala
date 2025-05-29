package cp.serverPr.nonThreadSafeImpl

import org.http4s.dsl.io._
import cats.effect.IO
import org.http4s._

object NonThreadSafeRoutes {
  private val sharedState: NonThreadSafeServerState = new NonThreadSafeServerState()

  val routes: IO[HttpRoutes[IO]] = IO.pure {
    HttpRoutes.of[IO] {
      case GET -> Root / "status" =>
        for {
          html <- sharedState.getStatusHtml
          response <- Ok(html)
            .map(addCORSHeaders)
            .map(_.withContentType(org.http4s.headers.`Content-Type`(MediaType.text.html)))
        } yield response

      case req @ GET -> Root / "run-process" =>
        val cmdOpt = req.uri.query.params.get("cmd")
        val userIp = req.remoteAddr.getOrElse("unknown")
        cmdOpt match {
          case Some(cmd) =>
            for {
              result <- sharedState.executeCommand(cmd, userIp.toString)
              response <- Ok(result).map(addCORSHeaders)
            } yield response
          case None =>
            BadRequest("Command not provided. Use /run-process?cmd=<your_command>").map(addCORSHeaders)
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