package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.rotate.LineMap;
import io.github.ajdailey.qlogg.core.text.EncodingDetection;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import io.github.ajdailey.qlogg.core.watch.FileMonitor;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The file state behind a {@link LogDocument}: the bytes, the line index, background indexing, the
 * watch and the {@link io.github.ajdailey.qlogg.core.concurrent.OperationGate} every operation on
 * the bytes enters. {@link SingleFileContent} holds one file or pasted text, {@link RotatedContent}
 * a rotated set. {@link LogDocument} keeps the marks, the reader settings, the listeners and the
 * close sequence, and checks its own closed state before it calls {@link #startIndexing} or {@link
 * #startWatching}. Thread-safe; every method except {@link #close} returns without I/O.
 */
interface DocumentContent {
  /**
   * Returns the name the document shows.
   *
   * @return the file name, or the name pasted text was given
   */
  String name();

  /**
   * Returns the path as given, absolute and normalized.
   *
   * @return the path, or null for content {@link #inMemory}
   */
  Path path();

  /**
   * Returns the real path of the file opened.
   *
   * @return the real path at open, or null for content {@link #inMemory}
   */
  Path realPath();

  /**
   * Reports whether the bytes are held in the heap.
   *
   * @return true for pasted text
   */
  boolean inMemory();

  /**
   * Reports whether the file is open only for each read.
   *
   * @return true when reads go through a {@link
   *     io.github.ajdailey.qlogg.core.io.ReopeningByteSource}
   */
  boolean keepsFileClosed();

  /**
   * Returns the encoding and the rule that chose it.
   *
   * @return the detection
   */
  EncodingDetection detection();

  /**
   * Returns the timestamp format detected at open.
   *
   * @return the format, or null when detection was skipped or found none
   */
  BuiltInFormat timeFormat();

  /**
   * Returns the bytes the index covers.
   *
   * @return the source
   */
  ByteSource bytes();

  /**
   * Returns the latest published line index.
   *
   * @return an immutable snapshot
   */
  LineIndex index();

  /**
   * Returns the map of the last {@link io.github.ajdailey.qlogg.core.watch.FileChange#ROLLED}
   * report.
   *
   * @return the map, or null before one and for content that never reports it
   */
  LineMap lineMap();

  /**
   * Returns the current state.
   *
   * @param closed whether the document is closed
   * @return the status
   */
  DocumentStatus status(boolean closed);

  /**
   * Admits an operation on the bytes; {@link #close} waits for it to {@link #exit}. Lock-free.
   *
   * @return false once closing
   */
  boolean enter();

  /** Ends an operation admitted by {@link #enter}. */
  void exit();

  /**
   * Starts indexing on a worker. Returns at once.
   *
   * @param events receives the indexing events and, once watching, the file changes
   * @throws IllegalStateException when indexing was started before or the content is closing
   * @throws java.util.concurrent.RejectedExecutionException after the workers close
   */
  void startIndexing(DocumentListener events);

  /**
   * Cancels indexing and leaves later growth unindexed.
   *
   * @return true when an indexing operation was running
   */
  boolean stopIndexing();

  /**
   * Starts watching on a {@code qlogg-follow-} thread; a second call, or a call for content {@link
   * #inMemory}, does nothing. Returns at once.
   *
   * @param monitor the file monitor
   * @param events receives the file changes and the indexing events of growth
   */
  void startWatching(FileMonitor monitor, DocumentListener events);

  /**
   * Records that the owner's reopen failed and resumes the checks.
   *
   * @return true when this failure starts a run of failures
   */
  boolean reopenFailed();

  /**
   * Reports whether a failed reopen waits for the next check.
   *
   * @return true from {@link #reopenFailed} until the run of failures ends
   */
  boolean reopenWaiting();

  /**
   * Refuses new operations, cancels indexing and the watch, waits up to the bound for every entered
   * operation to exit, then releases the index, closes the bytes and drops their blocks from the
   * shared cache. Blocking.
   *
   * @param bound the longest wait for the operations
   * @return false when an operation outlived the bound, which leaves the index reservation held, or
   *     the file failed to close
   */
  boolean close(Duration bound);
}
