package cp.serverPr.serverStates
import cp.serverPr.ServerStateInterface
import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global
import scala.sys.process._
import cats.effect.IO

class NonThreadSafeServerState extends ServerStateInterface {
  private val MAX_CONCURRENT_PROCESSES: Long = 7

  // Initialize semaphore eagerly to control concurrency
  private val semaphore: Semaphore[IO] = Semaphore[IO](MAX_CONCURRENT_PROCESSES).unsafeRunSync()

  // Simple mutable variables, no atomic operations or snapshots
  private var total: Int = 0
  private var running: Int = 0
  private var completed: Int = 0
  private var maxConcurrent: Int = 0
  private def queued: Int = total - running - completed

  def executeCommand(cmd: String, userIp: String): IO[String] = {
    for {
      processId <- IO {
        // Direct mutation without atomic operations
        total += 1
        total
      }
      // Semaphore permits only MAX_CONCURRENT_PROCESSES running at the same time
      result <- semaphore.permit.use { _ =>
        runCommand(processId, cmd, userIp)
      }
    } yield result
  }

  // Run command without semaphore control (semaphore is handled in executeCommand)
  private def runCommand(id: Int, cmd: String, userIp: String): IO[String] = {
    for {
      _ <- IO {
        // Update running count, not thread safe
        running += 1
        maxConcurrent = math.max(maxConcurrent, running)
      }
      result <- IO.blocking {
        try {
          val output = Process(Seq("bash", "-c", cmd)).!!
          s"[$id] Result from running $cmd user $userIp\n$output"
        } catch {
          case e: Exception =>
            s"[$id] Error running $cmd: ${e.getMessage}"
        }
      }
      _ <- IO {
        // Update completion stats - not thread safe
        running -= 1
        completed += 1
      }
    } yield result
  }

  // Direct access to mutable state without consistency guarantees
  def getStatusHtml: IO[String] = IO {
    s"""
       |<p><strong>counter:</strong> $total (Total commands received since server start)</p>
       |<p><strong>queued:</strong> $queued (Commands waiting for a semaphore permit / in queue)</p>
       |<p><strong>running:</strong> $running (Commands currently executing)</p>
       |<p><strong>completed:</strong> $completed (Commands that finished successfully)</p>
       |<p><strong>max concurrent:</strong> $maxConcurrent (Peak number of commands running simultaneously)</p>
       |<p><strong>Implementation:</strong> Non Thread Safe</p>
    """.stripMargin
  }
}