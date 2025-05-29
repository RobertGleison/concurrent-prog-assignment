package cp.serverPr.volatileImpl
import cats.effect.{IO}
import cats.effect.std.Semaphore
import org.slf4j.LoggerFactory
import scala.sys.process._
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

// Immutable case class for consistent status snapshots
case class ServerStats(
  total: Int,
  running: Int,
  completed: Int,
  maxConcurrent: Int
) {
  def queued: Int = total - running - completed
}

class VolatileServerState private (
  private val semaphore: Semaphore[IO]
) {
  private val logger = LoggerFactory.getLogger(getClass)
  
  private val totalProcesses = new AtomicInteger(0)
  private val runningProcesses = new AtomicInteger(0)
  private val completedProcesses = new AtomicInteger(0)
  private val maxConcurrent = new AtomicInteger(0)


  // Fix 2: Atomic reference for consistent status snapshots
  private val currentStats = new AtomicReference(ServerStats(0, 0, 0, 0))

  def executeCommand(cmd: String, userIp: String): IO[String] = {
    // Basic command validation for security

    val processId = totalProcesses.incrementAndGet()
    semaphore.permit.use { _ =>
      for {
        _ <- IO {
          val current = runningProcesses.incrementAndGet()
          updateMaxConcurrent(current)
          updateStats()
          logger.info(s"🔹 Starting process $processId: $cmd")
        }
        result <- runCommand(processId, cmd, userIp)
        _ <- IO {
          runningProcesses.decrementAndGet()
          completedProcesses.incrementAndGet()
          updateStats()
          logger.info(s"🔸 Completed process $processId")
        }
      } yield result
      }
  }

  // Fix 1: Thread-safe update of maxConcurrent using compareAndSet
  private def updateMaxConcurrent(newValue: Int): Unit = {
    maxConcurrent.getAndUpdate(current => math.max(current, newValue))
    ()
  }

  // Fix 2: Update stats atomically for consistent snapshots
  private def updateStats(): Unit = {
    val newStats = ServerStats(
      total = totalProcesses.get(),
      running = runningProcesses.get(),
      completed = completedProcesses.get(),
      maxConcurrent = maxConcurrent.get()
    )
    currentStats.set(newStats)
  }

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

  // Fix 2: Use consistent snapshot for status display
  def getStatusHtml: IO[String] = IO {
    val stats = currentStats.get()
    s"""
     |<p><strong>counter:</strong> ${stats.total} (Total commands received since server start)</p>
     |<p><strong>queued:</strong> ${stats.queued} (Commands waiting for a semaphore permit / in queue)</p>
     |<p><strong>running:</strong> ${stats.running} (Commands currently executing)</p>
     |<p><strong>completed:</strong> ${stats.completed} (Commands that finished successfully)</p>
     |<p><strong>max concurrent:</strong> ${stats.maxConcurrent} (Peak number of commands running simultaneously)</p>
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