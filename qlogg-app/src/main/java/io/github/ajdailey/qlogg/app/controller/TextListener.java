package io.github.ajdailey.qlogg.app.controller;

/** Receives text read on a worker, on the UI thread. */
public interface TextListener {
  /**
   * Called with the text.
   *
   * @param text the text read
   */
  void textRead(String text);
}
