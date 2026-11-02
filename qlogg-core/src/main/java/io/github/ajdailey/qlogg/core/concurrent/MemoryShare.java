package io.github.ajdailey.qlogg.core.concurrent;

/** A share of the maximum heap that one kind of structure draws from, across all documents. */
public enum MemoryShare {
  /** Line indexes: 40%. */
  LINE_INDEX(40),
  /** Search results, multi-tab results and marks: 20%. */
  RESULTS(20),
  /** Raw-byte block caches: 10%. */
  BLOCK_CACHE(10),
  /** Direct read buffers for indexing and search: 10%. */
  IO_BUFFERS(10),
  /** Fold maps: 3%. */
  FOLDS(3),
  /** Highlight spans and layout caches: 2%. */
  HIGHLIGHT(2),
  /** Text being copied to the clipboard: 5%. */
  COPY(5),
  /** Text opened from the clipboard, held while its document is open: 2%. */
  PASTED(2);

  private final int percent;

  MemoryShare(int percent) {
    this.percent = percent;
  }

  /**
   * Returns the share of the maximum heap.
   *
   * @return percent, 0-100
   */
  public int percent() {
    return percent;
  }
}
