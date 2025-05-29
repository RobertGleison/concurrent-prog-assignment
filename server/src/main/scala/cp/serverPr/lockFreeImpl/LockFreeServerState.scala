package cp.serverPr.lockFreeImpl

import cats.effect.IO
import org.slf4j.LoggerFactory
import scala.sys.process._
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

// Immutable state for the entire server
case class LockFreeServerStats(
  total: Int,
  running: Int,
  completed: Int,
  maxConcurrent: Int,
  availableSlots: Int // Track available execution slots
) {
  def queued: Int = total - running - completed
  def canExecute: Boolean = availableSlots > 0
}

class LockFreeServerState private (
  private val maxConcurrent: Int
) {
  private val logger = LoggerFactory.getLogger(getClass)

  // Single atomic reference for all state - this is the key to lock-free
  private val state = new AtomicReference(
    LockFreeServerStats(
      total = 0,
      running = 0,
      completed = 0,
      maxConcurrent = 0,
      availableSlots = maxConcurrent
    )
  )

  def executeCommand(cmd: String, userIp: String): IO[String] = {
    for {
      processId <- reserveSlot() // Lock-free slot reservation
      result <- processId match {
        case Some(id) =>
          runCommandWithCleanup(id, cmd, userIp)
        case None =>
          // No slots available - reject immediately (lock-free backpressure)
          IO.pure(s"❌ Server busy - max concurrent processes ($maxConcurrent) reached. Try again later.")
      }
    } yield result
  }

  // Lock-free slot reservation using compare-and-swap
  private def reserveSlot(): IO[Option[Int]] = IO {
    @tailrec
    def attemptReservation(): Option[Int] = {
      val currentState = state.get()

      if (!currentState.canExecute) {
        None // No slots available
      } else {
        val processId = currentState.total + 1
        val newRunning = currentState.running + 1
        val newState = currentState.copy(
          total = processId,
          running = newRunning,
          availableSlots = currentState.availableSlots - 1,
          maxConcurrent = math.max(currentState.maxConcurrent, newRunning)
        )

        if (state.compareAndSet(currentState, newState)) {
          Some(processId) // Successfully reserved slot
        } else {
          attemptReservation() // Retry - another thread modified state
        }
      }
    }

    attemptReservation()
  }

  // Release slot using compare-and-swap
  private def releaseSlot(): IO[Unit] = IO {
    @tailrec
    def attemptRelease(): Unit = {
      val currentState = state.get()
      val newState = currentState.copy(
        running = currentState.running - 1,
        completed = currentState.completed + 1,
        availableSlots = currentState.availableSlots + 1
      )

      if (!state.compareAndSet(currentState, newState)) {
        attemptRelease() // Retry if another thread modified state
      }
    }

    attemptRelease()
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

  // Lock-free status reading
  def getStatusHtml: IO[String] = IO {
    val currentState = state.get() // Single atomic read
    s"""
       |<p><strong>counter:</strong> ${currentState.total} (Total commands received since server start)</p>
       |<p><strong>queued:</strong> ${currentState.queued} (Commands waiting for execution)</p>
       |<p><strong>running:</strong> ${currentState.running} (Commands currently executing)</p>
       |<p><strong>completed:</strong> ${currentState.completed} (Commands that finished)</p>
       |<p><strong>max concurrent:</strong> ${currentState.maxConcurrent} (Peak number of commands running simultaneously)</p>
       |<p><strong>available slots:</strong> ${currentState.availableSlots} (Execution slots available right now)</p>
       """.stripMargin
  }
}

object LockFreeServerState {
  private val MAX_CONCURRENT_PROCESSES: Int = 3

  /**
   * Create lock-free server state
   */
  def create(): IO[LockFreeServerState] = {
    IO.pure(new LockFreeServerState(MAX_CONCURRENT_PROCESSES))
  }
}