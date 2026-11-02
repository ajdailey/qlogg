package io.github.ajdailey.qlogg.core.index;

import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records indexing events for assertions after the handle reports done. */
final class RecordingListener implements IndexListener {
  final List<IndexProgress> progress = new CopyOnWriteArrayList<IndexProgress>();
  volatile OperationOutcome outcome;
  volatile IOException failure;
  volatile int finishedCalls;

  @Override
  public void progressed(IndexProgress update) {
    progress.add(update);
  }

  @Override
  public void finished(OperationOutcome result, IOException cause) {
    outcome = result;
    failure = cause;
    finishedCalls++;
  }
}
