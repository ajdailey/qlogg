package io.github.ajdailey.qlogg.app.theme;

/** The system's light or dark setting, from the UI toolkit. Called on the UI thread. */
public interface ThemeService {
  /** Reports light and never changes; for a run without a toolkit setting. */
  ThemeService LIGHT =
      new ThemeService() {
        @Override
        public boolean systemDark() {
          return false;
        }

        @Override
        public void addListener(ThemeListener listener) {
          // The setting never changes.
        }
      };

  /**
   * Reports the system setting.
   *
   * @return true when the system prefers dark
   */
  boolean systemDark();

  /**
   * Adds a listener for changes.
   *
   * @param listener called on the UI thread
   */
  void addListener(ThemeListener listener);
}
