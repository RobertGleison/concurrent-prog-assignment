package cp.serverPr.raceConditionImpl

import cats.effect.IO
import org.slf4j.LoggerFactory
import scala.sys.process._
import scala.collection.mutable

// Simple mutable data class - NO thread safety
class UnsafeStats {
  // RACE CONDITION 2: Non-atomic compound operations on primitives
  var total: Int = 0
  var running: Int = 0
  var completed: Int = 0
  var maxConcurrent: Int = 0

  def queued: Int = total - running - completed  // Can be inconsistent!
  def availableSlots: Int = RaceConditionServerState.MAX_CONCURRENT_PROCESSES - running
  def canExecute: Boolean = running < RaceConditionServerState.MAX_CONCURRENT_PROCESSES

  // RACE CONDITION 3: Non-atomic read of multiple fields
  def getSnapshot: (Int, Int, Int, Int) = {
    Thread.sleep(1) // Artificial delay between reads
    val t = total
    Thread.sleep(1) // Another thread can modify state here!
    val r = running
    Thread.sleep(1)
    val c = completed
    Thread.sleep(1)
    val m = maxConcurrent
    (t, r, c, m)
  }
}

class RaceConditionServerState {
  private val logger = LoggerFactory.getLogger(getClass)

  private val stats = new UnsafeStats()

  // RACE CONDITION 5: Non-thread-safe collection
  private val activeProcesses = mutable.Set[Int]()
  private val waitingQueue = mutable.Queue[String]()

  def executeCommand(cmd: String, userIp: String): IO[String] = {
    for {
      processId <- reserveSlot()
      result <- processId match {
        case Some(id) =>
          runCommandWithCleanup(id, cmd, userIp)
        case None =>
          addToQueueUnsafely(cmd, userIp)
      }
    } yield result
  }

  // RACE CONDITION 6: Check-then-act race condition
  private def reserveSlot(): IO[Option[Int]] = IO {
    // Step 1: Check if we can execute (non-atomic)
    if (stats.canExecute) {

      // ARTIFICIAL DELAY - increases race condition window
      Thread.sleep(5)

      // Step 2: Multiple operations that should be atomic but aren't
      stats.total += 1                    // Another thread could
      Thread.sleep(1)                     // modify stats here!
      stats.running += 1
      Thread.sleep(1)
      val newRunning = stats.running
      Thread.sleep(1)

      // RACE CONDITION 7: Read-modify-write on shared variable
      if (newRunning > stats.maxConcurrent) {
        stats.maxConcurrent = newRunning  // Lost update possible
      }

      val processId = stats.total

      // RACE CONDITION 8: Modification of shared collection
      activeProcesses += processId        // Non-thread-safe collection

      logger.warn(s"⚠️ Reserved slot $processId. Running: ${stats.running}/${RaceConditionServerState.MAX_CONCURRENT_PROCESSES}")
      Some(processId)
    } else {
      None
    }
  }

  // RACE CONDITION 9: Non-atomic queue operations
  private def addToQueueUnsafely(cmd: String, userIp: String): IO[String] = IO {
    // Check queue size (race condition between check and enqueue)
    if (waitingQueue.size < RaceConditionServerState.MAX_QUEUE_SIZE) {
      Thread.sleep(2) // Increase race window
      waitingQueue.enqueue(s"$cmd from $userIp")  // Another thread could add here!

      val currentSize = waitingQueue.size
      s"📋 Added to queue. Position: $currentSize/${RaceConditionServerState.MAX_QUEUE_SIZE}"
    } else {
      s"❌ Queue full (${waitingQueue.size}). Try again later."
    }
  }

  // RACE CONDITION 10: Non-atomic release with multiple state updates
  private def releaseSlot(processId: Int): IO[Unit] = IO {
    // Multiple non-atomic operations
    stats.running -= 1                    // Can be interrupted
    Thread.sleep(1)                       // between these operations
    stats.completed += 1
    Thread.sleep(1)

    // RACE CONDITION 11: Collection modification race
    activeProcesses -= processId          // Non-thread-safe removal

    // RACE CONDITION 12: Process waiting queue unsafely
    if (waitingQueue.nonEmpty && stats.canExecute) {
      Thread.sleep(2) // Race window
      val nextCmd = waitingQueue.dequeue() // Could throw if emptied by another thread
      logger.warn(s"⚠️ Processing queued command: $nextCmd")
      // Would need to recursively call executeCommand here (omitted for simplicity)
    }

    logger.warn(s"⚠️ Released slot $processId. Running: ${stats.running}")
  }

  private def runCommandWithCleanup(id: Int, cmd: String, userIp: String): IO[String] = {
    val execution = for {
      _ <- IO(logger.info(s"🔹 Starting process $id: $cmd"))
      result <- runCommand(id, cmd, userIp)
      _ <- IO(logger.info(s"🔸 Completed process $id"))
    } yield result

    // Ensure slot is always released, even on failure
    execution.guarantee(releaseSlot(id))
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

  // RACE CONDITION 13: Reading inconsistent state
  def getStatusHtml: IO[String] = IO {
    // Read different fields at different times - can be inconsistent!
    val total = stats.total
    Thread.sleep(2)  // Let other threads modify state
    val running = stats.running
    Thread.sleep(2)
    val completed = stats.completed
    Thread.sleep(2)
    val maxConcurrent = stats.maxConcurrent
    Thread.sleep(2)

    // RACE CONDITION 14: Accessing collections without synchronization
    val activeCount = try {
      activeProcesses.size  // Could get ConcurrentModificationException
    } catch {
      case _: Exception => -1
    }

    val queueSize = try {
      waitingQueue.size     // Could get inconsistent size
    } catch {
      case _: Exception => -1
    }

    // Calculate derived values from potentially inconsistent state
    val queued = total - running - completed  // Can be negative!
    val available = RaceConditionServerState.MAX_CONCURRENT_PROCESSES - running

    s"""
       |<p><strong>⚠️ RACE CONDITION VERSION - DATA MAY BE INCONSISTENT! ⚠️</strong></p>
       |<p><strong>counter:</strong> $total (Total commands received)</p>
       |<p><strong>queued:</strong> $queued (Calculated: total - running - completed)</p>
       |<p><strong>running:</strong> $running (Commands currently executing)</p>
       |<p><strong>completed:</strong> $completed (Commands that finished)</p>
       |<p><strong>max concurrent:</strong> $maxConcurrent (Peak simultaneous commands)</p>
       |<p><strong>available slots:</strong> $available (Could be negative!)</p>
       |<p><strong>active processes count:</strong> $activeCount (From Set)</p>
       |<p><strong>queue size:</strong> $queueSize (From Queue)</p>
       |<p><strong>⚠️ INVARIANT VIOLATIONS TO WATCH FOR:</strong></p>
       |<ul>
       |<li>queued < 0 (negative queue)</li>
       |<li>running > MAX_CONCURRENT (exceeded limit)</li>
       |<li>total ≠ running + completed + queueSize (inconsistent totals)</li>
       |<li>activeCount ≠ running (collection/counter mismatch)</li>
       |</ul>
       """.stripMargin
  }

  // Method to demonstrate data races in action
  def stressTest(): IO[String] = IO {
    val results = (1 to 100).map { i =>
      val (t, r, c, m) = stats.getSnapshot
      s"Read $i: total=$t, running=$r, completed=$c, max=$m, calculated_queued=${t-r-c}"
    }.mkString("\n")

    s"""
       |STRESS TEST RESULTS (should show inconsistent values):
       |$results
       """.stripMargin
  }
}

object RaceConditionServerState {
  val MAX_CONCURRENT_PROCESSES: Int = 3
  val MAX_QUEUE_SIZE: Int = 5
}