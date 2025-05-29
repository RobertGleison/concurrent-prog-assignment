package cp.serverPr.serverStates

import java.util.concurrent.atomic.AtomicInteger
import cats.effect.unsafe.implicits.global
import cp.serverPr.ServerStateInterface
import cats.effect.std.Semaphore
import scala.sys.process._
import cats.effect.IO


class ManualLockFreeServerState extends ServerStateInterface{
  private val MAX_CONCURRENT_PROCESSES: Long = 3
  // Lock-free semaphore creation
  private val semaphore: Semaphore[IO] = Semaphore[IO](MAX_CONCURRENT_PROCESSES).unsafeRunSync()

  // Atomic variables for lock-free operations
  private val totalCounter = new AtomicInteger(0)
  private val runningCounter = new AtomicInteger(0)
  private val completedCounter = new AtomicInteger(0)
  private val maxConcurrentCounter = new AtomicInteger(0)

  def executeCommand(cmd: String, userIp: String): IO[String] = {
    // Atomically increment total and get process ID
    val processId = totalCounter.incrementAndGet()

    // Use semaphore to limit concurrent processes
    semaphore.permit.use { _ =>
      for {
        _ <- IO {
          // lock free manual increment running counter and update max concurrent
          val currentRunning = runningCounter.incrementAndGet()
          updateMaxConcurrent(currentRunning)
        }
        result <- runCommand(processId, cmd, userIp)
        _ <- IO {
          // Atomically decrement running and increment completed
          runningCounter.decrementAndGet()
          completedCounter.incrementAndGet()
        }
      } yield result
    }
  }

  // Lock-free update of max concurrent using compare-and-swap
  private def updateMaxConcurrent(currentRunning: Int): Unit = {
    var success = false
    while (!success) {
      val currentMax = maxConcurrentCounter.get()
      if (currentRunning > currentMax) {
        success = maxConcurrentCounter.compareAndSet(currentMax, currentRunning)
      } else {
        success = true // No update needed
      }
    }
  }

  // Run the command on a dedicated blocking thread pool
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

  // Lock-free status reading using atomic operations
  def getStatusHtml: IO[String] = IO {
    // Atomic reads are inherently consistent for individual variables
    val currentTotal = totalCounter.get()
    val currentRunning = runningCounter.get()
    val currentCompleted = completedCounter.get()
    val currentMaxConcurrent = maxConcurrentCounter.get()

    val queued = currentTotal - currentRunning - currentCompleted

    s"""
       |<p><strong>counter:</strong> $currentTotal (Total commands received since server start)</p>
       |<p><strong>queued:</strong> $queued (Commands waiting for a semaphore permit / in queue)</p>
       |<p><strong>running:</strong> $currentRunning (Commands currently executing)</p>
       |<p><strong>completed:</strong> $currentCompleted (Commands that finished successfully)</p>
       |<p><strong>max concurrent:</strong> $currentMaxConcurrent (Peak number of commands running simultaneously)</p>
    """.stripMargin
  }
}