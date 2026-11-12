package io.github.ajdailey.qlogg.app.view;

import io.github.ajdailey.qlogg.app.about.AboutInfo;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import io.github.ajdailey.qlogg.core.time.UserFormat;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * The main window as the controllers see it. Every method is called on the UI thread and returns
 * without waiting for the user, except {@link #chooseFilesToOpen}, which runs the platform chooser,
 * and the {@link ActivityIndicator} methods, which are callable from any thread.
 */
public interface MainView
    extends FileDialogService, ClipboardService, NotificationService, ActivityIndicator {
  /** Receives a line number the user entered. */
  interface LineNumberListener {
    /**
     * Called on the UI thread when the user confirms.
     *
     * @param line the 1-based line
     */
    void lineChosen(long line);
  }

  /** Receives a name the user entered. */
  interface NameListener {
    /**
     * Called on the UI thread when the user confirms.
     *
     * @param name the name as entered
     */
    void nameChosen(String name);
  }

  /**
   * Sets the window title.
   *
   * @param title the title
   */
  void setTitle(String title);

  /** Hides the window while shutdown completes. */
  void hide();

  /** Disposes the window, which ends the event loop. */
  void dispose();

  /**
   * Asks for a line number.
   *
   * @param lineCount lines indexed so far, shown as the range
   * @param listener called when the user confirms; not called on cancel
   */
  void askLineNumber(long lineCount, LineNumberListener listener);

  /** Moves keyboard focus to the search field. */
  void focusSearch();

  /** Shows the quick find bar, if hidden, and focuses its field. */
  void focusQuickFind();

  /**
   * Moves keyboard focus to a log view of the shown document.
   *
   * @param filtered true for the filtered view, false for the main view
   */
  void focusView(boolean filtered);

  /**
   * Shows or hides the overview bar.
   *
   * @param shown true shows it
   */
  void showOverview(boolean shown);

  /** Opens the highlighters editor. */
  void showHighlightersDialog();

  /**
   * Asks for a predefined filter's name.
   *
   * @param suggested the name the field starts with
   * @param listener called when the user confirms; not called on cancel
   */
  void askFilterName(String suggested, NameListener listener);

  /**
   * Moves the split between the main view and the filtered view.
   *
   * @param deltaPercent points of the window's height the main view gains; negative shrinks it
   */
  void resizeMainView(int deltaPercent);

  /**
   * Returns the main view's share of the split.
   *
   * @return percent of the split's height
   */
  int splitPercent();

  /** Opens the search history editor. */
  void showHistoryDialog();

  /** Opens the predefined filters editor. */
  void showFiltersDialog();

  /** Shows the keyboard shortcut reference. */
  void showKeyboardShortcuts();

  /**
   * Shows read-only text with a Copy button.
   *
   * @param title the window title
   * @param text the text
   */
  void showText(String title, String text);

  /**
   * Shows the About dialog.
   *
   * @param info what it shows
   */
  void showAbout(AboutInfo info);

  /** Shows the open files with a filter field; choosing one shows its tab. */
  void showOpenedFiles();

  // Mark comments and marks list.

  /**
   * Asks for a mark comment.
   *
   * @param line the 0-based line
   * @param current the comment the field starts with
   * @param listener called when the user confirms; not called on cancel
   */
  void askMarkComment(long line, String current, NameListener listener);

  /** Shows the marks list window, creating it on first use. */
  void showMarks();

  /**
   * Asks for a tab's name.
   *
   * @param current the name the field starts with
   * @param listener called when the user confirms; not called on cancel
   */
  void askTabName(String current, NameListener listener);

  /**
   * Opens a file or folder in the application the system associates with it, without a shell.
   *
   * @param target an absolute path
   * @return false when no application took it
   */
  boolean launch(Path target);

  /**
   * Reports whether {@link #launch} picks the handler by file extension, as on Windows, where a
   * file with an executable extension runs instead of opening.
   *
   * @return true when the extension decides
   */
  boolean launchesByExtension();

  /**
   * Describes the UI toolkit for About.
   *
   * @return version and platform, for example {@code SWT 4971 gtk}
   */
  String toolkitDescription();

  // Scratchpad, search across open files and automatic highlighter sets.

  /**
   * Shows the scratchpad window, creating it on first use.
   *
   * @param text text to add to the current tab, or null for none
   * @param replace true to replace the tab's text instead of appending
   */
  void showScratchpad(String text, boolean replace);

  /** Shows the window listing the search across open files, one group per file. */
  void showMultiSearch();

  // Search in a folder.

  /** Shows the Search in Folder window, creating it on first use. */
  void showFolderSearch();

  /** Shows the Automatic Highlighters dialog. */
  void showSelectorsDialog();

  // Options dialog, zoom and remembered window state.

  /** Opens the Options dialog. */
  void showOptions();

  /**
   * Returns the platform's default font size for the log views.
   *
   * @return points
   */
  int platformFontSize();

  /**
   * Returns the window's normal (unmaximized) position and client-area size.
   *
   * @return the bounds; null fields are unknown
   */
  WindowGeometry.Request windowBounds();

  /**
   * Reports whether the window is maximized.
   *
   * @return true when maximized
   */
  boolean windowMaximized();

  // Multiple windows, logging and support items.

  /** Raises the window and activates it, as far as the window system allows. */
  void toFront();

  /**
   * Shows or hides the status bar's warning marker; clicking it runs Show Log.
   *
   * @param shown true shows it
   */
  void showWarningMarker(boolean shown);

  /**
   * Shows the recent log lines in one modeless window at its first line, Close focused; a second
   * call replaces the text and brings the window to the front.
   *
   * @param text the lines
   */
  void showLog(String text);

  /**
   * Opens a fixed web address in the OS browser, without a shell.
   *
   * @param url the address
   * @return false when no application took it
   */
  boolean launchUrl(String url);

  // User guide.

  /**
   * Shows the user guide in a window with an embedded browser, or brings that window to the front.
   * When the browser fails or the page does not load in time after this returned true, the view
   * closes the window and calls {@code ApplicationController.showGuideElsewhere}.
   *
   * @param file the guide's HTML file
   * @return false when the browser fails before the window opens
   */
  boolean showGuide(Path file);

  // Timestamps.

  /** Receives a time or an offset the user entered. */
  interface TimeListener {
    /**
     * Called on the UI thread when the user confirms.
     *
     * @param nanos epoch nanoseconds, or an offset in nanoseconds
     */
    void timeChosen(long nanos);
  }

  /** Receives the user timestamp formats the user saved. */
  interface FormatsListener {
    /**
     * Called on the UI thread when the user confirms.
     *
     * @param formats the formats in order
     */
    void formatsChosen(List<UserFormat> formats);
  }

  /**
   * Asks for a Go to Time target, read by {@link TimeText#parseTarget}.
   *
   * @param initial the field's text: the caret line's time, or empty
   * @param zone the display zone the text is in
   * @param caretDate the date of a time entered without one, or null to refuse such a time
   * @param listener receives the target as shown, offset included
   */
  void askGoToTime(String initial, ZoneId zone, LocalDate caretDate, TimeListener listener);

  /**
   * Asks for the shown tab's time offset, as an offset or as the time the caret line should show.
   *
   * @param offset the current offset
   * @param caret the caret line's time without the offset, or {@link TimeText#INVALID} for none
   * @param caretDigits fraction digits of the caret line's time
   * @param zone the display zone
   * @param listener receives the offset; 0 clears it
   */
  void askTimeOffset(long offset, long caret, int caretDigits, ZoneId zone, TimeListener listener);

  /**
   * Asks for the zone of the shown file's local times.
   *
   * @param current the chosen zone's id, or empty for the default
   * @param defaultZone the zone {@code time.zone} gives
   * @param listener receives a zone id, or empty for the default
   */
  void askTimeZone(String current, ZoneId defaultZone, NameListener listener);

  /**
   * Shows the user timestamp format editor.
   *
   * @param formats the formats in order
   * @param caretLine the start of the shown file's caret line for the preview, or empty
   * @param context the shown file's zone and date for the preview
   * @param zone the display zone of the preview
   * @param listener receives the formats on OK
   */
  void showTimestampFormats(
      List<UserFormat> formats,
      String caretLine,
      TimeContext context,
      ZoneId zone,
      FormatsListener listener);

  // Command palette.

  /** Shows the command palette with its filter field focused. */
  void showCommandPalette();

  // Always on Top.

  /**
   * Reports whether {@link #setOnTop} takes effect on this platform.
   *
   * @return false where the window system or toolkit offers no way, or after a call failed
   */
  boolean onTopSupported();

  /**
   * Keeps the window above other windows, or returns it to normal stacking, without moving,
   * resizing or activating it. Does nothing where unsupported.
   *
   * @param onTop true to keep it above
   */
  void setOnTop(boolean onTop);
}
