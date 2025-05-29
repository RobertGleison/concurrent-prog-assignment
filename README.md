
## Concurrent Programming Assignment - Server State Management

## Overview
This project demonstrates different approaches to handle concurrent requests in Scala. It implements three different server state management strategies, each showcasing different concurrency control mechanisms and thread safety approaches. After that, a stress test was applied to test the non thread safety implementations x thread safety implementations. Concepts used: semaphores, lock-free, syncronized blocks, volatile variables, atomic variables.

## Project Structure

![alt text](image-1.png)

- The serverStates folder keeps different versions of Server Management.
- Routes is where the endpoins for requests are in
- Server is where the server is started


## Implementations

### 1. NonThreadSafeServerState
- Simple mutable variables for state tracking
- Semaphore for concurrency control (max N concurrent processes)
- No concurrency control mechanisms
- Direct variable mutations without atomic operations
- Suitable for single-threaded or testing environments
- **Risk**: Race conditions in concurrent scenarios

### 2. SynchronizedServerState
- Uses `volatile` variables for thread-safe visibility
- Synchronized blocks with `lockObject` for atomic state updates
- Semaphore for concurrency control (max N concurrent processes)
- Fully thread-safe implementation using traditional synchronization
- Prevents race conditions and ensures state consistency

### 3. AtomicLockFreeServerState
- Uses immutable `ServerStats` case class for consistent snapshots
- `AtomicReference` for lock-free thread-safe state updates
- Semaphore for concurrency control (max N concurrent processes)
- **Fully thread-safe** implementation
- Prevents race conditions and ensures state consistency

## State Metrics

Each implementation tracks the following metrics:

| Metric | Description |
|--------|-------------|
| **counter** | Total commands received since server start |
| **queued** | Commands waiting for execution (total - running - completed) |
| **running** | Commands currently executing |
| **completed** | Commands that finished successfully |
| **max concurrent** | Peak number of commands running simultaneously |

## Concurrency Control

The semaphore-based implementations use `cats.effect.std.Semaphore` to:

- ✅ Limit concurrent bash command execution
- ✅ Prevent system resource exhaustion
- ✅ Provide natural queuing for excess requests
- ✅ Maintain responsive server behavior under load


## Dependencies

```scala
// Required dependencies
- Scala 2.13+ or Scala 3
- Cats Effect 3.x
- cats.effect.std.Semaphore
- scala.sys.process for shell command execution
```

## Configuration

**MAX_CONCURRENT_PROCESSES**: Default is 3, can be modified in each implementation

- Controls how many bash commands can run simultaneously
- Prevents server overload
- Balances performance with resource usage

```scala
private val MAX_CONCURRENT_PROCESSES: Long = 3  // Modify as needed
```

## Error Handling

- Commands that fail return error messages with process ID
- Exceptions are caught and formatted as user-friendly error responses
- Server continues operation even when individual commands fail
- State is properly updated regardless of command success/failure

OBS: this code runs in test environments. I am not treating possible malicious linux command like `sudo` or `rm`

**Example error output:**
```
[42] Error running invalid-command: Cannot run program "invalid-command"
```


## UI Interface for testing

![alt text](image-2.png)


## Stress Test
![alt text](<Screenshot from 2025-05-29 22-05-54.png>)![alt text](<Screenshot from 2025-05-29 22-07-23.png>) ![alt text](<Screenshot from 2025-05-29 22-07-36.png>) ![alt text](<Screenshot from 2025-05-29 22-07-41.png>) ![alt text](<Screenshot from 2025-05-29 22-10-00.png>) ![alt text](<Screenshot from 2025-05-29 22-10-40.png>) ![alt text](<Screenshot from 2025-05-29 22-10-48.png>) ![alt text](<Screenshot from 2025-05-29 22-11-47.png>) ![alt text](<Screenshot from 2025-05-29 22-12-01.png>) ![alt text](<Screenshot from 2025-05-29 22-12-07.png>) ![alt text](<Screenshot from 2025-05-29 22-12-17.png>)
