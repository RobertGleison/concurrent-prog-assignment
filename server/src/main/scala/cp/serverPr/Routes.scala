package cp.serverPr

import org.http4s.dsl.io._
import cats.effect.IO
import org.http4s._

trait ServerStateInterface {
  def executeCommand(cmd: String, userIp: String): IO[String]
  def getStatusHtml: IO[String]
}


object Routes {
  def createRoutes(serverState: ServerStateInterface): IO[HttpRoutes[IO]] = IO.pure {
    HttpRoutes.of[IO] {
      case GET -> Root / "status" =>
        for {
          html <- serverState.getStatusHtml
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
              result <- serverState.executeCommand(cmd, userIp.toString)
              response <- Ok(result).map(addCORSHeaders)
            } yield response
          case None =>
            BadRequest("Command not provided. Use /run-process?cmd=<your_command>").map(addCORSHeaders)
        }
    }
  }

  private def addCORSHeaders(response: Response[IO]): Response[IO] = {
    response.putHeaders(
      "Access-Control-Allow-Origin" -> "*",
      "Access-Control-Allow-Methods" -> "GET, POST, PUT, DELETE, OPTIONS",
      "Access-Control-Allow-Headers" -> "Content-Type, Authorization",
      "Access-Control-Allow-Credentials" -> "true"
    )
  }
}