package io.github.ajdailey.qlogg.ui.swt.platform;

/**
 * Recreates every main window's controls over its controllers, for a native theme that existing
 * controls do not take. Called by {@link ThemeFollow#showOnWindows} on the UI thread; the windows
 * are rebuilt on later UI turns, never inside the caller.
 */
public interface WindowRebuild {
  /** Rebuilds nothing; the theme service holds it until the launcher supplies the windows. */
  WindowRebuild NONE =
      new WindowRebuild() {
        @Override
        public void rebuild() {}
      };

  /** Schedules a rebuild of every main window open now. */
  void rebuild();
}
