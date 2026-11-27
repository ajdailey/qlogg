package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.view.DocumentViewListener;
import io.github.ajdailey.qlogg.app.view.DocumentViewState;
import java.nio.file.Path;

/**
 * Goes to a member's line in a rotated set's tab. While indexing has not reached the line, the jump
 * waits on the tab's state changes and goes there once the set's index holds it; a member line is
 * never applied as a set line. A later jump, or the tab closing, drops a waiting one. Confined to
 * the UI thread.
 */
final class SetJump {
  /** A jump that waits on a tab. */
  private record Waiting(DocumentController target, DocumentViewListener listener) {}

  private Waiting waiting;

  /**
   * Goes to a member's line now, or once the set's index holds it.
   *
   * @param document a rotated set's tab
   * @param file the member's path
   * @param memberLine the 0-based line in that member
   */
  void go(final DocumentController document, final Path file, final long memberLine) {
    cancel();
    long line = RotatedTabs.setLine(document.document(), file, memberLine);
    if (line >= 0) {
      document.goToLine(line + 1);
      return;
    }
    DocumentViewListener listener =
        new DocumentViewListener() {
          @Override
          public void stateChanged(DocumentViewState state) {
            retry(document, file, memberLine);
          }

          @Override
          public void contentChanged() {
            retry(document, file, memberLine);
          }
        };
    waiting = new Waiting(document, listener);
    document.addListener(listener);
  }

  /** Drops a waiting jump. */
  void cancel() {
    Waiting dropped = waiting;
    if (dropped != null) {
      waiting = null;
      dropped.target().removeListener(dropped.listener());
    }
  }

  /** Goes to the line once the index holds it; the jump stops waiting first, as the move fires. */
  private void retry(DocumentController document, Path file, long memberLine) {
    if (waiting == null || document != waiting.target()) {
      return;
    }
    if (document.isClosed()) {
      cancel();
      return;
    }
    long line = RotatedTabs.setLine(document.document(), file, memberLine);
    if (line >= 0) {
      cancel();
      document.goToLine(line + 1);
    }
  }
}
