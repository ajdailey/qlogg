package io.github.ajdailey.qlogg.app.scratch;

/** Receives a scratchpad transform's outcome on the UI thread. */
public interface TransformListener {
  /**
   * The transform finished.
   *
   * @param output the output
   */
  void transformed(String output);

  /**
   * The transform refused the input.
   *
   * @param message the notice to show
   */
  void failed(String message);
}
