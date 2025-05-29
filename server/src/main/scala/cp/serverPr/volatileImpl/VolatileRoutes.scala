package cp.serverPr.volatileImpl

import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._
import org.slf4j.LoggerFactory

object VolatileRoutes {
  private val logger = LoggerFactory.getLogger(getClass)
  private val state = new VolatileServerState()

  val routes: IO[HttpRoutes[IO]] = IO {
    HttpRoutes.of[IO] {
      // React to a "status" request
      case GET -> Root / "status" =>
        Ok(state.toHtml)
          .map(addCORSHeaders)
          .map(_.withContentType(org.http4s.headers.`Content-Type`(MediaType.text.html)))

      // React to a "run-process" request
      case req @ GET -> Root / "run-process" =>
        val cmdOpt = req.uri.query.params.get("cmd")
        val userIp = req.remoteAddr.getOrElse("unknown")
        logger.debug(s">>> got run-process!")
        logger.debug(s">>> Cmd: ${cmdOpt}")
        logger.debug(s">>> userIP: $userIp")

        cmdOpt match {
          case Some(cmd) =>
            queueProcessRequest(cmd, userIp.toString)
              .flatMap(result => Ok(result))
              .map(addCORSHeaders)
          case None =>
            BadRequest("⚠️ Command not provided. Use /run-process?cmd=<your_command>")
              .map(addCORSHeaders)
        }
    }
  }

  /**
   * Queue a process request using volatile variables with careful coordination
   */
  private def queueProcessRequest(cmd: String, userIp: String): IO[String] = {
    IO.blocking {
      val requestId = state.incrementCounter()
      logger.info(s"🔹 Received process request ($requestId) for user $userIp: $cmd")

      // Try to execute immediately
      if (state.canStartExecution()) {
        state.startExecution(requestId)
        logger.info(s"🚀 Executing immediately ($requestId)")
        executeProcess(cmd, userIp, requestId)
      } else {
        // Queue the request
        state.queueRequest(requestId, cmd, userIp)
        logger.info(s"⏳ Queued request ($requestId)")
        s"[$requestId] Request queued. Queue size: ${state.getQueueSize()}"
      }
    }
  }

  /**
   * Execute a process
   */
  private def executeProcess(cmd: String, userIp: String, requestId: Int): String = {
    try {
      // Simulate work
      Thread.sleep(2000)
      val output = s"[$requestId] Result from running '$cmd' for user $userIp"
      logger.info(s"✅ Completed process ($requestId): $output")

      // Mark as completed and try to start next queued request
      state.completeExecution(requestId)
      processNextInQueue()

      output
    } catch {
      case _: InterruptedException =>
        logger.warn(s"⚠️ Process ($requestId) was interrupted")
        state.completeExecution(requestId)
        s"[$requestId] Process was interrupted"
    }
  }

  /**
   * Process the next request in the queue
   */
  private def processNextInQueue(): Unit = {
    state.getNextQueuedRequest() match {
      case Some((requestId, cmd, userIp)) =>
        logger.info(s"🚀 Starting queued process ($requestId)")
        // Execute in a separate thread
        val thread = new Thread(() => {
          val _ = executeProcess(cmd, userIp, requestId)
        })
        thread.start()
      case None =>
        logger.debug("No queued requests to process")
    }
  }

  /**
   * Add CORS headers
   */
  def addCORSHeaders(response: Response[IO]): Response[IO] = {
    response.putHeaders(
      "Access-Control-Allow-Origin" -> "*",
      "Access-Control-Allow-Methods" -> "GET, POST, PUT, DELETE, OPTIONS",
      "Access-Control-Allow-Headers" -> "Content-Type, Authorization",
      "Access-Control-Allow-Credentials" -> "true"
    )
  }
}