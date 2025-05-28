package cp.serverPr
import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._
import org.slf4j.LoggerFactory

object Routes {
  private val logger = LoggerFactory.getLogger(getClass)
  private val state = new ServerState()

  val routes: IO[HttpRoutes[IO]] =
    IO{HttpRoutes.of[IO] {
      // React to a "status" request
      case GET -> Root / "status" =>
        Ok(state.toHtml)
          .map(addCORSHeaders)
          .map(_.withContentType(org.http4s.headers.`Content-Type`(MediaType.text.html)))

      // React to a "run-process" request
      // Each request runs concurrently without blocking others
      case req@GET -> Root / "run-process" =>
        val cmdOpt = req.uri.query.params.get("cmd")
        val userIp = req.remoteAddr.getOrElse("unknown")

        logger.debug(s">>> got run-process!")
        logger.debug(s">>> Cmd: ${cmdOpt}")
        logger.debug(s">>> userIP: $userIp")

        cmdOpt match {
          case Some(cmd) =>
            // Run the process asynchronously using IO
            runProcessAsync(cmd, userIp.toString)
              .flatMap(result => Ok(result))
              .map(addCORSHeaders)
          case None =>
            BadRequest("⚠️ Command not provided. Use /run-process?cmd=<your_command>")
              .map(addCORSHeaders)
        }
    }}

  /** Run a process asynchronously without blocking other requests. */
  private def runProcessAsync(cmd: String, userIp: String): IO[String] = {
    IO.blocking {
      // Thread-safe counter increment
      val requestId = state.incrementCounter()
      logger.info(s"🔹 Starting process ($requestId) for user $userIp: $cmd")

      // TODO: Replace this with actual process execution
      // This sleep simulates a long-running process
      Thread.sleep(2000) // Simulating 2 seconds of work

      val output = s"[$requestId] Result from running '$cmd' for user $userIp"
      logger.info(s"✅ Completed process ($requestId): $output")
      output
    }
  }

  /** Add extra headers, required by the client. */
  def addCORSHeaders(response: Response[IO]): Response[IO] = {
    response.putHeaders(
      "Access-Control-Allow-Origin" -> "*",
      "Access-Control-Allow-Methods" -> "GET, POST, PUT, DELETE, OPTIONS",
      "Access-Control-Allow-Headers" -> "Content-Type, Authorization",
      "Access-Control-Allow-Credentials" -> "true"
    )
  }
}