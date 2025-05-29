package cp.serverPr.raceConditionImpl

import scala.collection.mutable

/**
 * BAD IMPLEMENTATION - Intentional race conditions for demonstration
 */
class RaceConditionServerState {
  private val MAX_CONCURRENT = 3

  // Plain variables - no synchronization or atomicity
  private var _counter = 0
  private var _currentlyExecuting = 0
  private val _executingRequests = mutable.Set[Int]()
  private val _requestQueue = mutable.Queue[(Int, String, String)]()

  def counter: Int = _counter

  /**
   * RACE CONDITION: This increment is not atomic!
   * Multiple threads can read the same value and increment simultaneously,
   * causing lost updates.
   */
  def incrementCounter(): Int = {
    val current = _counter  // Read
    Thread.sleep(1)         // Artificial delay to increase race chance
    _counter = current + 1  // Write
    _counter
  }

  /**
   * RACE CONDITION: The check is separate from the subsequent increment
   */
  def canExecuteImmediately(): Boolean = {
    _currentlyExecuting < MAX_CONCURRENT
  }

  /**
   * RACE CONDITION: Multiple operations that should be atomic
   * The increment and set addition are separate operations
   */
  def startExecution(requestId: Int): Unit = {
    val current = _currentlyExecuting  // Read
    Thread.sleep(1)                    // Artificial delay
    _currentlyExecuting = current + 1  // Write (lost updates possible)
    _executingRequests += requestId    // Non-thread-safe collection
  }

  /**
   * RACE CONDITION: Decrement and removal are not atomic
   */
  def completeExecution(requestId: Int): Unit = {
    val current = _currentlyExecuting
    _currentlyExecuting = current - 1  // Race condition
    _executingRequests -= requestId    // Non-thread-safe collection
  }

  /**
   * RACE CONDITION: Queue operations are not thread-safe
   */
  def queueRequest(requestId: Int, cmd: String, userIp: String): Unit = {
    _requestQueue.enqueue((requestId, cmd, userIp))  // Can cause corruption
  }

  /**
   * RACE CONDITION: Multiple check-and-modify operations
   */
  def getNextQueuedRequest(): Option[(Int, String, String)] = {
    if (_requestQueue.nonEmpty && _currentlyExecuting < MAX_CONCURRENT) {
      val request = _requestQueue.dequeue()  // Can throw exception if empty
      val current = _currentlyExecuting
      _currentlyExecuting = current + 1      // Race condition
      _executingRequests += request._1       // Non-thread-safe
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