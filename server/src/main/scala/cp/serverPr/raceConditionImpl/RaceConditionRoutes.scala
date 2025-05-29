package cp.serverPr.raceConditionImpl

import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._
import org.slf4j.LoggerFactory

/**
 * Contains intentional race conditions for demonstration
 *
 * 1. Counter increment race: Multiple threads can read the same counter value
 *    and increment it simultaneously, causing lost updates.
 *
 * 2. Execution limit race: The check for available slots and the increment
 *    of currentlyExecuting are not atomic, allowing more than MAX_CONCURRENT
 *    processes to run simultaneously.
 *
 * 3. Collection modification race: Non-thread-safe collections are modified
 *    concurrently without synchronization.
 *
 * EXPECTED PROBLEMS:
 * - Counter may show duplicate or skipped numbers
 * - More than 3 processes executing simultaneously
 * - Inconsistent queue sizes and execution counts
 * - Possible exceptions from concurrent collection modifications
 */

object RaceConditionRoutes {
  private val logger = LoggerFactory.getLogger(getClass)
  private val state = new RaceConditionServerState()

  val routes: IO[HttpRoutes[IO]] = IO {
    HttpRoutes.of[IO] {
      case GET -> Root / "status" =>
        Ok(state.toHtml)
          .map(addCORSHeaders)
          .map(_.withContentType(org.http4s.headers.`Content-Type`(MediaType.text.html)))

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

  private def queueProcessRequest(cmd: String, userIp: String): IO[String] = {
    IO.blocking {
      // RACE CONDITION: Counter increment is not atomic
      val requestId = state.incrementCounter()
      logger.info(s"🔹 Received process request ($requestId) for user $userIp: $cmd")

      // RACE CONDITION: Check and increment are separate operations
      if (state.canExecuteImmediately()) {
        // Small delay to increase chance of race condition
        Thread.sleep(1)
        state.startExecution(requestId)
        logger.info(s"🚀 Executing immediately ($requestId)")
        executeProcess(cmd, userIp, requestId)
      } else {
        state.queueRequest(requestId, cmd, userIp)
        logger.info(s"⏳ Queued request ($requestId)")
        s"[$requestId] Request queued. Queue size: ${state.getQueueSize()}"
      }
    }
  }

  private def executeProcess(cmd: String, userIp: String, requestId: Int): String = {
    try {
      Thread.sleep(2000)
      val output = s"[$requestId] Result from running '$cmd' for user $userIp"
      logger.info(s"✅ Completed process ($requestId): $output")

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

  private def processNextInQueue(): Unit = {
    state.getNextQueuedRequest() match {
      case Some((requestId, cmd, userIp)) =>
        logger.info(s"🚀 Starting queued process ($requestId)")
        val thread = new Thread(() => {
          val _ = executeProcess(cmd, userIp, requestId)
        })
        thread.start()
      case None =>
        logger.debug("No queued requests to process")
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