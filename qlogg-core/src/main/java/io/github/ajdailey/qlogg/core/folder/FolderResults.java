package io.github.ajdailey.qlogg.core.folder;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import java.util.ArrayList;
import java.util.List;

/**
 * The files of one folder search, in the order the search lists them, holding their {@code RESULTS}
 * reservations until {@link #close}. A search adds; any thread reads. Thread-safe.
 */
public final class FolderResults {
  private final MemoryBudget budget;
  private final List<FileHits> files = new ArrayList<FileHits>();
  private long matches;
  private long retained;
  private boolean limited;
  private boolean closed;

  /**
   * Creates empty results.
   *
   * @param budget the budget entries' {@code RESULTS} reservations return to
   */
  public FolderResults(MemoryBudget budget) {
    this.budget = budget;
  }

  /** Takes an entry and its reservation; after {@link #close} releases the reservation at once. */
  synchronized void add(FileHits file) {
    if (closed) {
      budget.release(MemoryShare.RESULTS, file.retainedBytes());
      return;
    }
    files.add(file);
    matches += file.matches();
    retained += file.retainedBytes();
  }

  /** Records that the results share stopped the search. */
  synchronized void setLimited() {
    limited = true;
  }

  /**
   * Counts the listed files.
   *
   * @return entries
   */
  public synchronized int fileCount() {
    return files.size();
  }

  /**
   * Returns one entry.
   *
   * @param i from 0 to {@link #fileCount} - 1
   * @return the entry
   */
  public synchronized FileHits file(int i) {
    return files.get(i);
  }

  /**
   * Returns the entries.
   *
   * @return a new list
   */
  public synchronized List<FileHits> files() {
    return new ArrayList<FileHits>(files);
  }

  /**
   * Counts the matching lines of every searched file.
   *
   * @return the sum of {@link FileHits#matches}
   */
  public synchronized long matches() {
    return matches;
  }

  /**
   * Reports whether the {@code RESULTS} share stopped the search.
   *
   * @return true once a refused reservation ended it
   */
  public synchronized boolean limited() {
    return limited;
  }

  /**
   * Returns the bytes the entries hold.
   *
   * @return bytes reserved from {@code RESULTS}
   */
  public synchronized long retainedBytes() {
    return retained;
  }

  /**
   * Reports whether {@link #close} ran.
   *
   * @return true once closed
   */
  public synchronized boolean isClosed() {
    return closed;
  }

  /** Drops the entries and releases their reservations; later additions are released at once. */
  public synchronized void close() {
    closed = true;
    budget.release(MemoryShare.RESULTS, retained);
    retained = 0;
    matches = 0;
    files.clear();
  }
}
