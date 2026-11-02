package io.github.ajdailey.qlogg.core.search;

import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.text.TextEncoding;

/** A document's lines as a search reads them. */
public interface LineSource {
  /**
   * Returns the latest published index snapshot.
   *
   * @return an immutable snapshot
   */
  LineIndex index();

  /**
   * Returns the open file. A search reads it with positional reads and never closes it.
   *
   * @return the source
   */
  ByteSource bytes();

  /**
   * Returns the encoding.
   *
   * @return the encoding
   */
  TextEncoding encoding();
}
