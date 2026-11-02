package io.github.ajdailey.qlogg.core.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Identifies an open document for the life of the process.
 *
 * @param value unique, increasing
 */
public record DocumentId(long value) {
  private static final AtomicLong NEXT = new AtomicLong(1);

  /**
   * Returns a new identifier.
   *
   * @return the identifier
   */
  public static DocumentId next() {
    return new DocumentId(NEXT.getAndIncrement());
  }
}
