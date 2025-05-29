package cp.serverPr.volatileImpl

import cats.effect.{IO, Ref}
import cats.effect.std.{Queue, Semaphore}
import org.slf4j.LoggerFactory
import scala.sys.process._
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

case class ProcessRequest(id: Int, cmd: String, userIp: String)

/**
 * Server state containing all business logic for process management
 * Uses atomic variables for thread-safe operations and volatile for visibility
 */
class VolatileServerState private (
  private val semaphore: Semaphore[IO],
  private val processQueue: Queue[IO, ProcessRequest],
  private val resultMap: Ref[IO, Map[Int, String]]
) {

  private val logger = LoggerFactory.getLogger(getClass)



  // ATOMIC VARIABLES: Thread-safe counters
  private val processCounter = new AtomicInteger(0)     // Total processes created
  private val queuedProcesses = new AtomicInteger(0)    // Currently queued
  private val runningProcesses = new AtomicInteger(0)   // Currently running
  private val completedProcesses = new AtomicInteger(0) // Completed processes

  // VOLATILE VARIABLES: Visibility across threads
  @volatile private var maxConcurrent: Int = 0          // Peak concurrent processes

  /**
   * Submit a new process for execution
   * Returns immediately with a confirmation message
   */
  def submitProcess(cmd: String, userIp: String): IO[String] = {
    for {
      processId <- IO(incrementCounter())
      _ <- IO(queueProcess())
      processReq = ProcessRequest(processId, cmd, userIp)
      _ <- processQueue.offer(processReq)
      _ <- IO(logger.info(s"🔶 Queued process ${processId}: $cmd"))
      result <- waitForProcessCompletion(processId)
    } yield result
  }

  /**
   * Get current server status as HTML
   */
  def getStatusHtml: IO[String] = IO {
    toHtml
  }

  /**
   * Generate next process ID and update counter
   */
  private def incrementCounter(): Int = {
    processCounter.incrementAndGet()
  }

  /**
   * Increment queued counter
   */
  private def queueProcess(): Unit = {
    queuedProcesses.incrementAndGet()
    ()
  }

  /**
   * Move process from queued to running state
   */
  private def startProcess(): Unit = {
    // Move from queued to running
    queuedProcesses.decrementAndGet()
    val currentRunning = runningProcesses.incrementAndGet()

    // Update max concurrent if we hit a new peak (volatile write)
    if (currentRunning > maxConcurrent) {
      maxConcurrent = currentRunning
    }
  }

  /**
   * Mark process as completed
   */
  private def completeProcess(): Unit = {
    runningProcesses.decrementAndGet()
    completedProcesses.incrementAndGet()
    () // Explicitly return Unit
  }

  /**
   * Wait for a process to complete and return its result
   */
  private def waitForProcessCompletion(processId: Int): IO[String] = {
    def checkResult: IO[Option[String]] =
      resultMap.get.map(_.get(processId))

    def pollForResult: IO[String] =
      checkResult.flatMap {
        case Some(result) => IO.pure(result)
        case None =>
          IO.sleep(100.millis) >> pollForResult
      }

    pollForResult
  }

  /**
   * Execute a single process request
   */
  private def executeProcess(processReq: ProcessRequest): IO[String] = {
    IO.blocking {
      try {
        logger.info(s"🔹 Starting process ${processReq.id}: ${processReq.cmd}")
        val output = Process(Seq("bash", "-c", processReq.cmd)).!!
        val result = s"[${processReq.id}] Result from running ${processReq.cmd} user ${processReq.userIp}\n$output"
        logger.info(s"🔸 Completed process ${processReq.id}")
        result
      } catch {
        case e: Exception =>
          val errorResult = s"[${processReq.id}] Error running ${processReq.cmd}: ${e.getMessage}"
          logger.error(s"Process ${processReq.id} failed: ${e.getMessage}")
          errorResult
      }
    }
  }

  /**
   * Main process worker - handles queued processes with concurrency control
   */
  private def processWorker: IO[Unit] = {
    processQueue.take.flatMap { processReq =>
      // Semaphore controls max concurrent processes
      semaphore.permit.use { _ =>
        for {
          _ <- IO(startProcess())
          result <- executeProcess(processReq)
          _ <- resultMap.update(_.updated(processReq.id, result))
          _ <- IO(completeProcess())
        } yield ()
      }
    }.handleErrorWith { error =>
      IO(logger.error(s"Error in process worker: ${error.getMessage}", error))
    } >> processWorker // Continue processing
  }

  /**
   * Generate HTML status display
   */
  def toHtml: String = {
    // Read atomic values (thread-safe)
    val counter = processCounter.get()
    val queued = queuedProcesses.get()
    val running = runningProcesses.get()  // Added running processes count
    val completed = completedProcesses.get()

    // Read volatile value (guaranteed fresh)
    val maxConcurrentValue = maxConcurrent

    s"""
      |<p><strong>counter:</strong> $counter</p>
      |<p><strong>queued:</strong> $queued</p>
      |<p><strong>running:</strong> $running</p>
      |<p><strong>completed:</strong> $completed</p>
      |<p><strong>max concurrent:</strong> $maxConcurrentValue</p>
    """.stripMargin
  }

  /**
   * Start the background process worker
   */
  private def startWorker: IO[Unit] = {
    processWorker.start.void
  }
}

object VolatileServerState {
  private val MAX_CONCURRENT_PROCESSES: Long = 3

  /**
   * Create a new server state instance with all necessary components
   */
  def create(): IO[VolatileServerState] = {
    for {
      semaphore <- Semaphore[IO](MAX_CONCURRENT_PROCESSES) // Max 3 concurrent processes
      processQueue <- Queue.unbounded[IO, ProcessRequest]
      resultMap <- Ref.of[IO, Map[Int, String]](Map.empty)
      state = new VolatileServerState(semaphore, processQueue, resultMap)
      _ <- state.startWorker // Start the background worker
    } yield state
  }
}