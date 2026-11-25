package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.KeySequencer;
import io.github.ajdailey.qlogg.app.command.KeyStroke;
import io.github.ajdailey.qlogg.app.command.Keymap;
import io.github.ajdailey.qlogg.app.i18n.UiText;

/**
 * A log view's {@link KeySequencer} with the pending prefix's lifetime: the listener receives the
 * prefix text when a sequence starts and empty text when it completes, is canceled by a key, times
 * out after {@link #TIMEOUT_MILLIS} or is canceled by {@link #cancel}. Confined to the UI thread.
 */
final class SequenceKeys {
  /** How long a prefix stays pending without a second stroke. */
  static final int TIMEOUT_MILLIS = 5000;

  private final KeySequencer keys;
  private final SwtUiDispatcher timers;
  private final LogCanvas.PrefixListener listener;
  private final int timeoutMillis;
  private final Runnable timeout =
      new Runnable() {
        @Override
        public void run() {
          cancel();
        }
      };

  /**
   * Creates the keys.
   *
   * @param keymap the bindings
   * @param timers runs the timeout
   * @param listener receives the prefix text, empty when none is pending
   * @param timeoutMillis how long a prefix stays pending
   */
  SequenceKeys(
      Keymap keymap, SwtUiDispatcher timers, LogCanvas.PrefixListener listener, int timeoutMillis) {
    this.keys = new KeySequencer(keymap);
    this.timers = timers;
    this.listener = listener;
    this.timeoutMillis = timeoutMillis;
  }

  /**
   * Resolves a key press and reports the prefix.
   *
   * @param stroke the press
   * @return what to do
   */
  KeySequencer.Result press(KeyStroke stroke) {
    KeySequencer.Result result = keys.press(stroke);
    if (result instanceof KeySequencer.Pending pending) {
      timers.timerExec(timeoutMillis, timeout);
      listener.prefixChanged(UiText.STATUS_KEY_PREFIX.format(pending.prefix().text(SwtKeys.mac())));
    } else {
      timers.cancelTimer(timeout);
      listener.prefixChanged("");
    }
    return result;
  }

  /**
   * Returns whether a prefix is pending.
   *
   * @return true between a sequence's first and second stroke
   */
  boolean pending() {
    return keys.prefix() != null;
  }

  /** Drops a pending prefix and reports empty text; does nothing when none is pending. */
  void cancel() {
    if (keys.prefix() == null) {
      return;
    }
    keys.reset();
    timers.cancelTimer(timeout);
    listener.prefixChanged("");
  }
}
