package io.github.ajdailey.qlogg.app.view;

import io.github.ajdailey.qlogg.app.about.AboutInfo;
import java.nio.file.Path;

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
}
