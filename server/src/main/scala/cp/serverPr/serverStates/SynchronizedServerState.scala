package cp.serverPr.serverStates

import cats.effect.unsafe.implicits.global
import cp.serverPr.ServerStateInterface
import cats.effect.std.Semaphore
import scala.sys.process._
import cats.effect.IO


class SynchronizedServerState  extends  ServerStateInterface{
  private val MAX_CONCURRENT_PROCESSES: Long = 3
  // unsafeSync to already generate semaphore instead of only structions to create lazy
  private val semaphore: Semaphore[IO] = Semaphore[IO](MAX_CONCURRENT_PROCESSES).unsafeRunSync()

  // Volatile variables for thread-safe visibility
  @volatile private var total: Int = 0
  @volatile private var running: Int = 0
  @volatile private var completed: Int = 0
  @volatile private var maxConcurrent: Int = 0

  // Object for synchronization
  private val lockObject = new Object()


  def executeCommand(cmd: String, userIp: String): IO[String] = {
    // Lock to increment total and get process ID without interference
    val processId = lockObject.synchronized {
      total += 1
      total
    }

    // semaphore permits only MAX_CONCURRENT_PROCESSES running at the same time
    semaphore.permit.use { _ =>
      for {
        _ <- IO {
          // Lock to update running and max concurrent counts without interference before command start
          lockObject.synchronized {
            running += 1
            if (running > maxConcurrent) {
              maxConcurrent = running
            }
          }
        }
        result <- runCommand(processId, cmd, userIp)
        _ <- IO {
          // Lock to update running and completed counts without interference after command finished
          lockObject.synchronized {
            running -= 1
            completed += 1
          }
        }
      } yield result
    }
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


  // Get status using volatile variables and a read lock
  def getStatusHtml: IO[String] = IO {
    val (currentTotal, currentRunning, currentCompleted, currentMaxConcurrent) =
    lockObject.synchronized {
      (total, running, completed, maxConcurrent)
    }
    val queued = currentTotal - currentRunning - currentCompleted

    s"""
       |<p><strong>counter:</strong> $currentTotal (Total commands received since server start)</p>
       |<p><strong>queued:</strong> $queued (Commands waiting for a semaphore permit / in queue)</p>
       |<p><strong>running:</strong> $currentRunning (Commands currently executing)</p>
       |<p><strong>completed:</strong> $currentCompleted (Commands that finished successfully)</p>
       |<p><strong>max concurrent:</strong> $currentMaxConcurrent (Peak number of commands running simultaneously)</p>
       |<p><strong>Implementation:</strong> Syncronized Blocks</p>
    """.stripMargin
  }
}