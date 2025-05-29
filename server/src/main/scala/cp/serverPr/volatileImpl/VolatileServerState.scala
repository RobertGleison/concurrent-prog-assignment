package cp.serverPr.volatileImpl
import cats.effect.{IO}
import cats.effect.std.Semaphore
import scala.sys.process._
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import cats.effect.unsafe.implicits.global


// Immutable Snashot of my server state.
// This solve consistency problems of updating different variables at different times
// Just one snapshot is created when GET status is called
case class ServerStats(total: Int,
                       running: Int,
                       completed: Int,
                       maxConcurrent: Int) {
  def queued: Int = total - running - completed
}

class VolatileServerState {
  private val MAX_CONCURRENT_PROCESSES: Long = 3
  private val semaphore: Semaphore[IO] = Semaphore[IO](MAX_CONCURRENT_PROCESSES).unsafeRunSync()
  private val currentStats = new AtomicReference(ServerStats(0, 0, 0, 0))

  private val totalProcesses = new AtomicInteger(0)
  private val runningProcesses = new AtomicInteger(0)
  private val completedProcesses = new AtomicInteger(0)
  private val maxConcurrent = new AtomicInteger(0)

  def executeCommand(cmd: String, userIp: String): IO[String] = {
    val processId = totalProcesses.incrementAndGet() // Atomically increments and get the value as id of the process

    // semaphore permits only MAX_CONCURRENT_PROCESSES running at the same time
    semaphore.permit.use { _ =>
      for {
        _ <- IO {
          val current = runningProcesses.incrementAndGet()
          updateMaxConcurrent(current)          // update max concurrent commands with thread-safety
          updateStats()                         // update stats with thread-safety
        }
        result <- runCommand(processId, cmd, userIp)
        _ <- IO {
          runningProcesses.decrementAndGet()    // decrement the number of commands running because its completed with thread-safety
          completedProcesses.incrementAndGet()  // increment the number of completed commands with thread-safety
          updateStats()                         // update stats with thread-safety
        }
      } yield result
      }
  }

  private def updateMaxConcurrent(newValue: Int): Unit = {
    maxConcurrent.getAndUpdate(current => math.max(current, newValue))
    ()
  }

  // single atomic container
  private def updateStats(): Unit = {
    val newStats = ServerStats(
      total = totalProcesses.get(),
      running = runningProcesses.get(),
      completed = completedProcesses.get(),
      maxConcurrent = maxConcurrent.get()
    )
    currentStats.set(newStats)
  }

  // run the command on a dedicated blocking thread pool
  private def runCommand(id: Int, cmd: String, userIp: String): IO[String] = {
    IO.blocking {
      try {
        val output = Process(Seq("bash", "-c", cmd)).!!
        s"[$id] Result from running $cmd user $userIp\n$output"
      } catch {
        case e: Exception =>
          val errorMsg = s"[$id] Error running $cmd: ${e.getMessage}"
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
