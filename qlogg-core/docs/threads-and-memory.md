# Threads and memory budget

`core.concurrent`: the thread owner, background operations and the heap
budget. Module overview: [qlogg-core](../README.md).

## Workers

`Workers` owns every thread QLogg starts. No other code creates a thread, an
executor, a `ThreadFactory` or a `java.util.Timer`; `ArchitectureTest` in
`qlogg-launcher` enforces it. The launcher creates one instance and closes it
at shutdown.

| Work | Runs on | Thread names |
|---|---|---|
| CPU-bound (`executeCpu`) | Fixed pool of daemon platform threads, bounded queue of 4,096 | `qlogg-cpu-N` |
| Blocking (`start(Kind, Runnable)`) | A virtual thread per task | `qlogg-index-`, `qlogg-read-`, `qlogg-search-`, `qlogg-follow-`, `qlogg-ipc-`, `qlogg-lifecycle-`, `qlogg-watchdog-` |
| JVM shutdown hook (`shutdownHook(task)`) | One platform thread, returned unstarted and outside the live set; `close` neither waits for nor interrupts it | `qlogg-shutdown` |

- Every task runs inside a wrapper that passes the exception it ends with to
  the failure handler given to the constructor, which is also each thread's
  uncaught exception handler. No task returns a `Future`.
- After `close`, `start` and `executeCpu` throw `RejectedExecutionException`;
  `tryStart` returns false instead.
- `close(bound)` rejects new tasks, waits up to the bound for running ones,
  then interrupts what is left (`shutdownNow` for the pool). A virtual thread
  that calls `close` does not wait for itself. `liveThreads()` counts the
  threads that have not ended; `liveThreadNames()` names them for a log line.
- No task starts after `close` returns: `start` adds the thread to the live
  set, then checks `closed` again before starting it, and `close` reads the
  set until it holds no other thread.
- Pool threads are daemon threads, so a missed `close` cannot keep the JVM
  alive.
- Code that catches `InterruptedException` restores the interrupt flag and
  returns.

## Background operations

A background operation returns an `OperationHandle` (`cancel`, `isDone`,
`await`) and ends `COMPLETED`, `CANCELED` or `FAILED`. Its listener's final
call comes before `isDone()` turns true. `CancellationToken.check()` throws
`CancellationException` once canceled.

`OperationGate` counts the operations using a resource. `enter()` admits one
until `close()`, `exit()` ends it, and `awaitIdle` returns once the gate is
closed and the count is zero. `enter` and `exit` are lock-free, so the UI
thread may call them.

## Memory budget

`MemoryBudget` divides `Runtime.maxMemory()` (a fixed size in tests) into
shares that all open documents draw from:

| Share | Percent | Holder |
|---|---|---|
| `LINE_INDEX` | 40 | `GrowableLineIndex` |
| `RESULTS` | 20 | Search results, marks, mark comments, marks list rows, quiet trigger lines, folder search results |
| `BLOCK_CACHE` | 10 | `BlockCache`, at most 64 MiB |
| `IO_BUFFERS` | 10 | `ReadBuffers` |
| `COPY` | 5 | Text being copied to the clipboard or saved |
| `FOLDS` | 3 | Fold maps |
| `HIGHLIGHT` | 2 | Highlight and layout caches |
| `PASTED` | 2 | Text opened from the clipboard, while its document is open |

The remaining 8% is headroom.

A structure calls `reserve` before it allocates and `release` when it closes.
A refused reservation makes the structure degrade instead of failing with
`OutOfMemoryError`. `charge` reserves without the limit check, for the minimum
a structure needs to work at all.
