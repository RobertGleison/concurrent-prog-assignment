package cp.serverPr.volatileImpl

import cats.effect.{IO}
import cats.effect.std.Semaphore
import org.slf4j.LoggerFactory
import scala.sys.process._
import java.util.concurrent.atomic.AtomicInteger

/**
 * Simplified server state - just execute commands with concurrency control
 */
class VolatileServerState private (
  private val semaphore: Semaphore[IO]
) {
  private val logger = LoggerFactory.getLogger(getClass)

  // Simple atomic counters for stats
  private val totalProcesses = new AtomicInteger(0)
  private val runningProcesses = new AtomicInteger(0)
  private val completedProcesses = new AtomicInteger(0)

  // Track peak concurrent processes
  @volatile private var maxConcurrent: Int = 0

  /**
   * Execute command and return result when ready
   */
  def executeCommand(cmd: String, userIp: String): IO[String] = {
    val processId = totalProcesses.incrementAndGet()

    semaphore.permit.use { _ =>
      for {
        _ <- IO {
          val current = runningProcesses.incrementAndGet()
          if (current > maxConcurrent) maxConcurrent = current
          logger.info(s"🔹 Starting process $processId: $cmd")
        }
        result <- runCommand(processId, cmd, userIp)
        _ <- IO {
          runningProcesses.decrementAndGet()
          completedProcesses.incrementAndGet()
          logger.info(s"🔸 Completed process $processId")
        }
      } yield result
    }
  }

  /**
   * Execute the actual command
   */
  private def runCommand(id: Int, cmd: String, userIp: String): IO[String] = {
    IO.blocking {
      try {
        val output = Process(Seq("bash", "-c", cmd)).!!
        s"[$id] Result from running $cmd user $userIp\n$output"
      } catch {
        case e: Exception =>
          val errorMsg = s"[$id] Error running $cmd: ${e.getMessage}"
          logger.error(errorMsg)
          errorMsg
      }
    }
  }

  /**
   * Get server status as HTML
   */
  def getStatusHtml: IO[String] = IO {
    val total = totalProcesses.get()
    val running = runningProcesses.get()
    val completed = completedProcesses.get()
    val queued = total - running - completed // Simple queue calculation

    s"""
      |<p><strong>counter:</strong> $total</p>
      |<p><strong>queued:</strong> $queued</p>
      |<p><strong>running:</strong> $running</p>
      |<p><strong>completed:</strong> $completed</p>
      |<p><strong>max concurrent:</strong> $maxConcurrent</p>
    """.stripMargin
  }
}

object VolatileServerState {
  private val MAX_CONCURRENT_PROCESSES: Long = 3

  /**
   * Create server state with semaphore for concurrency control
   */
  def create(): IO[VolatileServerState] = {
    Semaphore[IO](MAX_CONCURRENT_PROCESSES).map(new VolatileServerState(_))
  }
}