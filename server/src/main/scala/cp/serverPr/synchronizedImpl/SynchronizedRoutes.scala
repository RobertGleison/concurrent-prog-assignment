package cp.serverPr.synchronizedImpl

import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._
import org.slf4j.LoggerFactory

object SynchronizedRoutes {
  private val logger = LoggerFactory.getLogger(getClass)
  private val state = new SynchronizedServerState()

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
            // Queue the process request
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
   * Queue a process request or execute immediately if capacity allows
   */
  private def queueProcessRequest(cmd: String, userIp: String): IO[String] = {
    IO.blocking {
      val requestId = state.incrementCounter()
      logger.info(s"🔹 Received process request ($requestId) for user $userIp: $cmd")

      // Check if we can execute immediately or need to queue
      if (state.canExecuteImmediately()) {
        state.startExecution(requestId)
        logger.info(s"🚀 Executing immediately ($requestId)")
        executeProcess(cmd, userIp, requestId)
      } else {
        // Queue the request
        state.queueRequest(requestId, cmd, userIp)
        logger.info(s"⏳ Queued request ($requestId)")
        s"[$requestId] Request queued. Position in queue: ${state.getQueuePosition(requestId)}"
      }
    }
  }

  /**
   * Execute a process (simulated with Thread.sleep)
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
   * Process the next request in the queue if any
   */
  private def processNextInQueue(): Unit = {
    state.getNextQueuedRequest() match {
      case Some((requestId, cmd, userIp)) =>
        logger.info(s"🚀 Starting queued process ($requestId)")
        // Execute in a separate thread to avoid blocking
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