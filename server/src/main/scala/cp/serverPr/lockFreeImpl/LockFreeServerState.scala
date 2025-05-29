package cp.serverPr.lockFreeImpl

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentLinkedQueue, ConcurrentHashMap}

case class QueuedRequest(requestId: Int, cmd: String, userIp: String)

class LockFreeServerState {
  private val MAX_CONCURRENT = 3
  private val _counter = new AtomicInteger(0)
  private val _currentlyExecuting = new AtomicInteger(0)
  private val _executingRequests = ConcurrentHashMap.newKeySet[Int]()
  private val _requestQueue = new ConcurrentLinkedQueue[QueuedRequest]()

  def counter: Int = _counter.get()

  def incrementCounter(): Int = _counter.incrementAndGet()

  /**
   * Try to start execution using Compare-And-Swap
   * Returns true if execution can start immediately
   */
  def tryStartExecution(requestId: Int): Boolean = {
    var currentCount = _currentlyExecuting.get()
    while (currentCount < MAX_CONCURRENT) {
      if (_currentlyExecuting.compareAndSet(currentCount, currentCount + 1)) {
        val _ = _executingRequests.add(requestId)  // Explicitly discard boolean return
        return true
      }
      // Retry if CAS failed due to concurrent modification
      currentCount = _currentlyExecuting.get()
    }
    false
  }

  def completeExecution(requestId: Int): Unit = {
    _currentlyExecuting.decrementAndGet()  // Explicitly discard int return
    val _ = _executingRequests.remove(requestId)   // Explicitly discard boolean return
  }

  def queueRequest(requestId: Int, cmd: String, userIp: String): Unit = {
    val _ = _requestQueue.offer(QueuedRequest(requestId, cmd, userIp))  // Explicitly discard boolean return
  }

  /**
   * Get next queued request using lock-free approach
   */
  def getNextQueuedRequest(): Option[(Int, String, String)] = {
    val request = _requestQueue.poll()
    if (request != null && tryStartExecution(request.requestId)) {
      Some((request.requestId, request.cmd, request.userIp))
    } else if (request != null) {
      // Put it back if we couldn't start execution
      val _ = _requestQueue.offer(request)  // Explicitly discard boolean return
      None
    } else {
      None
    }
  }

  def getQueueSize(): Int = _requestQueue.size()

  def toHtml: String = {
    val currentExec = _currentlyExecuting.get()
    val queueSize = _requestQueue.size()
    val executingList = _executingRequests.toArray().mkString(", ")

    s"""
      |<div>
      |  <p><strong>Counter:</strong> ${counter}</p>
      |  <p><strong>Currently Executing:</strong> $currentExec / $MAX_CONCURRENT</p>
      |  <p><strong>Queue Size:</strong> $queueSize</p>
      |  <p><strong>Executing Requests:</strong> $executingList</p>
      |  <p style="color: green;"><strong>✅ Lock-free implementation</strong></p>
      |</div>
    """.stripMargin
  }
}