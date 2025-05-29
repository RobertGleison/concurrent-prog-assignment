package cp.serverPr.synchronizedImpl

import scala.collection.mutable.Queue

class SynchronizedServerState {
  private val MAX_CONCURRENT = 3 // Maximum concurrent executions
  private var _counter = 0
  private var _currentlyExecuting = 0
  private val _executingRequests = scala.collection.mutable.Set[Int]()
  private val _requestQueue = Queue[(Int, String, String)]() // (requestId, cmd, userIp)

  def counter: Int = synchronized { _counter }

  def incrementCounter(): Int = synchronized {
    _counter += 1
    _counter
  }

  def canExecuteImmediately(): Boolean = synchronized {
    _currentlyExecuting < MAX_CONCURRENT
  }

  def startExecution(requestId: Int): Unit = synchronized {
    _currentlyExecuting += 1
    val _ = _executingRequests += requestId  // Explicitly discard return value
  }

  def completeExecution(requestId: Int): Unit = synchronized {
    _currentlyExecuting -= 1
    val _ = _executingRequests -= requestId  // Explicitly discard return value
  }

  def queueRequest(requestId: Int, cmd: String, userIp: String): Unit = synchronized {
    _requestQueue.enqueue((requestId, cmd, userIp))
  }

  def getNextQueuedRequest(): Option[(Int, String, String)] = synchronized {
    if (_requestQueue.nonEmpty && _currentlyExecuting < MAX_CONCURRENT) {
      val request = _requestQueue.dequeue()
      _currentlyExecuting += 1
      val _ = _executingRequests += request._1  // Explicitly discard return value
      Some(request)
    } else {
      None
    }
  }

  def getQueuePosition(requestId: Int): Int = synchronized {
    _requestQueue.zipWithIndex.find(_._1._1 == requestId) match {
      case Some((_, index)) => index + 1
      case None => -1
    }
  }

  def toHtml: String = synchronized {
    val currentExec = _currentlyExecuting     // It's an Int, not AtomicInteger
    val queueSize = _requestQueue.size        // Property, not method
    val executingList = _executingRequests.mkString(", ")  // mutable.Set has mkString directly

    s"""
      |<div>
      |  <p><strong>Counter:</strong> ${counter}</p>
      |  <p><strong>Currently Executing:</strong> $currentExec / $MAX_CONCURRENT</p>
      |  <p><strong>Queue Size:</strong> $queueSize</p>
      |  <p><strong>Executing Requests:</strong> $executingList</p>
      |  <p style="color: blue;"><strong>🔒 Synchronized implementation</strong></p>
      |</div>
    """.stripMargin
  }
}