package cp.serverPr.serverStates

import cp.serverPr.ServerStateInterface
import scala.sys.process._
import cats.effect.IO

class NonThreadSafeServerState extends  ServerStateInterface{
  // simple mutable variables,  no atomic operations or snapshots
  private var total: Int = 0
  private var running: Int = 0
  private var completed: Int = 0
  private var maxConcurrent: Int = 0

  private def queued: Int = total - running - completed

  def executeCommand(cmd: String, userIp: String): IO[String] = {
    for {
      processId <- IO {
        // direct mutation without atomic operations
        total += 1
        total
      }
      result <- runCommand(processId, cmd, userIp)
    } yield result
  }

  // run command without any concurrency control
  private def runCommand(id: Int, cmd: String, userIp: String): IO[String] = {
    for {
      _ <- IO {
        // update running count, not thread safe
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
        // update completion stats - not thread safe
        running -= 1
        completed += 1
      }
    } yield result
  }

  // direct access to mutable state without consistency guarantees
  def getStatusHtml: IO[String] = IO {
    s"""
       |<p><strong>counter:</strong> $total (Total commands received since server start)</p>
       |<p><strong>queued:</strong> $queued (Commands waiting for a semaphore permit / in queue)</p>
       |<p><strong>running:</strong> $running (Commands currently executing)</p>
       |<p><strong>completed:</strong> $completed (Commands that finished successfully)</p>
       |<p><strong>max concurrent:</strong> $maxConcurrent (Peak number of commands running simultaneously)</p>
    """.stripMargin
  }}