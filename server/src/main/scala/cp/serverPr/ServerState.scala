package cp.serverPr
import java.util.concurrent.atomic.AtomicInteger

class ServerState() {
  // TODO: extend the state of the server
  // Use AtomicInteger for thread-safe counter operations
  private val _counter = new AtomicInteger(0)

  def counter: Int = _counter.get()
  def incrementCounter(): Int = _counter.incrementAndGet()

  def toHtml: String =
    s"<p><strong>counter:</strong> $counter</p>"
}