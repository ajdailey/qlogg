package io.github.ajdailey.qlogg.core.index;

import java.io.IOException;

/** The line index share cannot hold even a sparse index of the file. */
public final class IndexCapacityException extends IOException {
  private static final long serialVersionUID = 1L;

  private final long neededBytes;
  private final long availableBytes;

  /**
   * Creates the exception.
   *
   * @param neededBytes bytes the index asked for
   * @param availableBytes bytes the {@code LINE_INDEX} share had left
   */
  public IndexCapacityException(long neededBytes, long availableBytes) {
    super(
        "Line index needs "
            + neededBytes
            + " more bytes; the LINE_INDEX share has "
            + availableBytes
            + " available");
    this.neededBytes = neededBytes;
    this.availableBytes = availableBytes;
  }

  /**
   * Returns the refused amount.
   *
   * @return bytes
   */
  public long neededBytes() {
    return neededBytes;
  }

  /**
   * Returns what the share had left.
   *
   * @return bytes
   */
  public long availableBytes() {
    return availableBytes;
  }
}
