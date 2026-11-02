package io.github.ajdailey.qlogg.core.search;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.text.TextEncoding;

/** Creates line matchers. Thread-safe. */
public interface MatcherFactory {
  /**
   * Creates a matcher for one worker thread.
   *
   * @param query the query, including inverse and whole word
   * @param encoding the document's encoding
   * @param token checked during regex matches
   * @return the matcher
   * @throws java.util.regex.PatternSyntaxException when a regex term does not compile
   */
  LineMatcher create(SearchQuery query, TextEncoding encoding, CancellationToken token);
}
