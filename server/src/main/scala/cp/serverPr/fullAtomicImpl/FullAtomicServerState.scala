package cp.serverPr.fullAtomicImpl

import java.util.concurrent.atomic.AtomicReference
import cats.effect.unsafe.implicits.global
import cats.effect.std.Semaphore
import scala.sys.process._
import cats.effect.IO


// Immutable snapshot of my server state.
// This solve consistency problems of updating different variables at different times
// Just one snapshot is created when GET status is called
case class ServerStats(total: Int,
                       running: Int,
                       completed: Int,
                       maxConcurrent: Int) {
                       def queued: Int = total - running - completed
}


class FullAtomicServerState {

  private val MAX_CONCURRENT_PROCESSES: Long = 3
  // unsafeSync to already generate semaphore instead of only structions to create lazy
  private val semaphore: Semaphore[IO] = Semaphore[IO](MAX_CONCURRENT_PROCESSES).unsafeRunSync()
  private val currentStats = new AtomicReference(ServerStats(0, 0, 0, 0))


  def executeCommand(cmd: String, userIp: String): IO[String] = {
    // create a new snapshot atomically with updated values and get the total to create process id
    val processId = currentStats.updateAndGet(stats => stats.copy(total = stats.total + 1)).total

    // semaphore permits only MAX_CONCURRENT_PROCESSES running at the same time
    semaphore.permit.use { _ =>
      for {
        _ <- IO {
          // create a new snapshot atomically with updated values before a command starts
          currentStats.updateAndGet { stats =>
                                      val newRunning = stats.running + 1
                                      val newMaxConcurrent = math.max(stats.maxConcurrent, newRunning)
                                      stats.copy(
                                          running = newRunning,
                                          maxConcurrent = newMaxConcurrent
                                      )}}

        result <- runCommand(processId, cmd, userIp)
        _ <- IO {
          // create a new snapshot atomically with updated values after a command is completed
          currentStats.updateAndGet { stats =>
                                      stats.copy(
                                          running = stats.running - 1,
                                          completed = stats.completed + 1
                                      )}}
        } yield result }}


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
      }}}


  // Use consistent snapshot for status display
  def getStatusHtml: IO[String] = IO {
    val stats = currentStats.get()
    s"""
     |<p><strong>counter:</strong> ${stats.total} (Total commands received since server start)</p>
     |<p><strong>queued:</strong> ${stats.queued} (Commands waiting for a semaphore permit / in queue)</p>
     |<p><strong>running:</strong> ${stats.running} (Commands currently executing)</p>
     |<p><strong>completed:</strong> ${stats.completed} (Commands that finished successfully)</p>
     |<p><strong>max concurrent:</strong> ${stats.maxConcurrent} (Peak number of commands running simultaneously)</p>
    """.stripMargin
  }}
