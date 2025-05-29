package cp.serverPr.synchronizedImpl

import cats.effect.IO
import org.slf4j.LoggerFactory
import scala.sys.process._
import scala.collection.mutable

// Mutable case class for server stats - protected by synchronization
case class SynchronizedServerStats(
  var total: Int = 0,
  var running: Int = 0,
  var completed: Int = 0,
  var maxConcurrent: Int = 0
) {
  def queued: Int = total - running - completed
  def availableSlots: Int = SynchronizedServerState.MAX_CONCURRENT_PROCESSES - running
  def canExecute: Boolean = running < SynchronizedServerState.MAX_CONCURRENT_PROCESSES

  // Create immutable snapshot for safe reading
  def snapshot: SynchronizedServerStats =
    SynchronizedServerStats(total, running, completed, maxConcurrent)
}

class SynchronizedServerState private () {
  private val logger = LoggerFactory.getLogger(getClass)

  // Mutable state protected by synchronized blocks
  private val stats = SynchronizedServerStats()

  // Queue for waiting requests when at max capacity
  private val waitingQueue = mutable.Queue[() => Unit]()

  // Lock object for fine-grained synchronization
  private val statsLock = new Object()
  private val queueLock = new Object()

  def executeCommand(cmd: String, userIp: String): IO[String] = {
    for {
      processId <- reserveSlot()
      result <- processId match {
        case Some(id) =>
          runCommandWithCleanup(id, cmd, userIp)
        case None =>
          // Wait in queue or reject
          waitForSlotOrReject(cmd, userIp)
      }
    } yield result
  }

  // Synchronized slot reservation
  private def reserveSlot(): IO[Option[Int]] = IO {
    statsLock.synchronized {
      if (stats.canExecute) {
        stats.total += 1
        stats.running += 1
        stats.maxConcurrent = math.max(stats.maxConcurrent, stats.running)

        logger.info(s"🔹 Reserved slot. Running: ${stats.running}/${SynchronizedServerState.MAX_CONCURRENT_PROCESSES}")
        Some(stats.total)
      } else {
        None
      }
    }
  }

  // Synchronized slot release with queue processing
  private def releaseSlot(): IO[Unit] = IO {
    val nextTask: Option[() => Unit] = statsLock.synchronized {
      stats.running -= 1
      stats.completed += 1

      logger.info(s"🔸 Released slot. Running: ${stats.running}/${SynchronizedServerState.MAX_CONCURRENT_PROCESSES}")

      // Check if we can process waiting requests
      if (stats.canExecute) {
        queueLock.synchronized {
          if (waitingQueue.nonEmpty) {
            Some(waitingQueue.dequeue())
          } else {
            None
          }
        }
      } else {
        None
      }
    }

    // Execute next task outside of locks to prevent deadlock
    nextTask.foreach(_())
  }

  // Handle queuing logic with timeout
  private def waitForSlotOrReject(cmd: String, userIp: String): IO[String] = {
    IO.async_ { callback =>
      val queued = queueLock.synchronized {
        if (waitingQueue.size < SynchronizedServerState.MAX_QUEUE_SIZE) {
          // Add to queue
          waitingQueue.enqueue(() => {
            // This will be called when a slot becomes available
            val futureResult = for {
              slotOpt <- reserveSlot()
              result <- slotOpt match {
                case Some(id) => runCommandWithCleanup(id, cmd, userIp)
                case None => IO.pure(s"❌ Failed to acquire slot after queuing")
              }
            } yield result

            // Execute and callback with result
            futureResult.unsafeRunAsync {
              case Right(result) => callback(Right(result))
              case Left(error) => callback(Left(error))
            }
          })
          true
        } else {
          false
        }
      }

      if (!queued) {
        // Queue is full, reject immediately
        callback(Right(s"❌ Server overloaded - queue full (${SynchronizedServerState.MAX_QUEUE_SIZE}). Try again later."))
      }

      // If queued, the callback will be invoked when slot becomes available
      None // No cleanup needed
    }
  }

  private def runCommandWithCleanup(id: Int, cmd: String, userIp: String): IO[String] = {
    val execution = for {
      _ <- IO(logger.info(s"🔹 Starting process $id: $cmd"))
      result <- runCommand(id, cmd, userIp)
      _ <- IO(logger.info(s"🔸 Completed process $id"))
    } yield result

    // Ensure slot is always released, even on failure
    execution.guarantee(releaseSlot())
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

  // Synchronized status reading
  def getStatusHtml: IO[String] = IO {
    val currentStats = statsLock.synchronized {
      stats.snapshot // Create immutable snapshot under lock
    }

    val queueSize = queueLock.synchronized {
      waitingQueue.size
    }

    s"""
       |<p><strong>counter:</strong> ${currentStats.total} (Total commands received since server start)</p>
       |<p><strong>queued:</strong> $queueSize (Commands waiting in queue)</p>
       |<p><strong>running:</strong> ${currentStats.running} (Commands currently executing)</p>
       |<p><strong>completed:</strong> ${currentStats.completed} (Commands that finished)</p>
       |<p><strong>max concurrent:</strong> ${currentStats.maxConcurrent} (Peak number of commands running simultaneously)</p>
       |<p><strong>available slots:</strong> ${currentStats.availableSlots} (Execution slots available right now)</p>
       |<p><strong>queue capacity:</strong> ${SynchronizedServerState.MAX_QUEUE_SIZE - queueSize}/${SynchronizedServerState.MAX_QUEUE_SIZE} (Remaining queue space)</p>
       """.stripMargin
  }

  // Debug method to show detailed state
  def getDetailedStatus: IO[String] = IO {
    statsLock.synchronized {
      val queueSize = queueLock.synchronized(waitingQueue.size)
      s"""
         |Stats: total=${stats.total}, running=${stats.running}, completed=${stats.completed}
         |Max concurrent: ${stats.maxConcurrent}
         |Queue size: $queueSize/${SynchronizedServerState.MAX_QUEUE_SIZE}
         |Available slots: ${stats.availableSlots}
         """.stripMargin
    }
  }
}

object SynchronizedServerState {
  val MAX_CONCURRENT_PROCESSES: Int = 3
  val MAX_QUEUE_SIZE: Int = 10 // Maximum number of requests to queue

  /**
   * Create synchronized server state
   */
  def create(): IO[SynchronizedServerState] = {
    IO.pure(new SynchronizedServerState())
  }
}