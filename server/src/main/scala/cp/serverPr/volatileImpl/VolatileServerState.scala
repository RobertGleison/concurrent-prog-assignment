package cp.serverPr.volatileImpl

import scala.collection.mutable

class VolatileServerState {
  private val MAX_CONCURRENT = 3

  // Using volatile for visibility but note: this doesn't solve all concurrency issues
  @volatile private var _counter = 0
  @volatile private var _currentlyExecuting = 0

  // These collections are not thread-safe, which will cause issues
  private val _executingRequests = mutable.Set[Int]()
  private val _requestQueue = mutable.Queue[(Int, String, String)]()

  def counter: Int = _counter

  // This increment is NOT atomic - potential race condition
  def incrementCounter(): Int = {
    _counter += 1  // This is actually: read -> increment -> write (not atomic!)
    _counter
  }

  def canStartExecution(): Boolean = {
    _currentlyExecuting < MAX_CONCURRENT
  }

  // These operations are not atomic despite volatile
  def startExecution(requestId: Int): Unit = {
    _currentlyExecuting += 1  // Race condition here!
    _executingRequests += requestId  // Not thread-safe collection
  }

  def completeExecution(requestId: Int): Unit = {
    _currentlyExecuting -= 1  // Race condition here!
    _executingRequests -= requestId  // Not thread-safe collection
  }

  def queueRequest(requestId: Int, cmd: String, userIp: String): Unit = {
    _requestQueue.enqueue((requestId, cmd, userIp))  // Not thread-safe!
  }

  def getNextQueuedRequest(): Option[(Int, String, String)] = {
    if (_requestQueue.nonEmpty && _currentlyExecuting < MAX_CONCURRENT) {
      val request = _requestQueue.dequeue()  // Race condition possible!
      _currentlyExecuting += 1
      _executingRequests += request._1
      Some(request)
    } else {
      None
    }
  }

  def getQueueSize(): Int = _requestQueue.size

  def toHtml: String = {
    val currentExec = _currentlyExecuting
    val queueSize = _requestQueue.size
    val executingList = _executingRequests.mkString(", ")

    s"""
      |<div>
      |  <p><strong>Counter:</strong> ${counter}</p>
      |  <p><strong>Currently Executing:</strong> $currentExec / $MAX_CONCURRENT</p>
      |  <p><strong>Queue Size:</strong> $queueSize</p>
      |  <p><strong>Executing Requests:</strong> $executingList</p>
      |</div>
    """.stripMargin
  }
}