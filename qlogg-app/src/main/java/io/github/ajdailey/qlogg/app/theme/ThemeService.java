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

        @Override
        public void preferDark(boolean dark) {
          // No native controls.
        }

        @Override
        public boolean showsNow(boolean dark) {
          return true;
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

  /**
   * Asks the toolkit to draw native controls dark or light. Does not change {@link #systemDark}.
   *
   * @param dark true for dark controls
   */
  void preferDark(boolean dark);

  /**
   * Reports whether native controls show a {@link #preferDark} request now.
   *
   * @param dark the request
   * @return true when they show it now; false when they show it from the next start
   */
  boolean showsNow(boolean dark);
}
