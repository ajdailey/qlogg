package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.about.AboutInfo;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.view.BusyCounter;
import io.github.ajdailey.qlogg.app.view.MainView;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import io.github.ajdailey.qlogg.core.time.UserFormat;
import io.github.ajdailey.qlogg.core.util.os.OsTraits;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * A main window's {@link MainView} that outlives the window: every call goes to the current {@link
 * MainWindow}, so the {@link ApplicationController}, its sub-controllers and their hosts keep one
 * view across a {@link #rebuild}. The busy count lives here, so an operation that starts before a
 * rebuild and ends after it keeps the count balanced and the new window shows the busy cursor.
 * Created by {@link WindowViews}; confined to the UI thread except {@link #setActive} and {@link
 * #setIdle}.
 */
public final class MainWindowSlot implements MainView {
  private final BusyCounter busy;
  private MainView view;
  private MainWindow window;
  private BusyCounter.Indicator cursor;
  private ApplicationController controller;

  /**
   * Creates the slot over a new window.
   *
   * @param window the window, not yet attached
   */
  MainWindowSlot(MainWindow window) {
    this(window, new SwtUiDispatcher(window.shell().getDisplay()));
    this.window = window;
    this.cursor = new WindowBusyCursor(window.shell());
  }

  /**
   * Creates a slot forwarding to a view, with no window to rebuild.
   *
   * @param view the view
   * @param ui runs the busy count's changes
   */
  MainWindowSlot(MainView view, UiDispatcher ui) {
    this.view = view;
    this.busy =
        new BusyCounter(
            ui,
            new BusyCounter.Indicator() {
              @Override
              public void busyChanged(boolean active) {
                if (cursor != null) {
                  cursor.busyChanged(active);
                }
              }
            });
  }

  /**
   * Connects the window to its controller, with close requests going to {@link
   * ApplicationController#closeWindow}.
   *
   * @param application the controller created over this slot
   */
  public void attach(ApplicationController application) {
    controller = application;
    window.attach(application);
    window.onCloseRequest(application::closeWindow);
  }

  /**
   * Returns the current window.
   *
   * @return the window a rebuild last created, or the first one
   */
  public MainWindow window() {
    return window;
  }

  /**
   * Returns the operation counter behind the busy cursor.
   *
   * @return the counter
   */
  public BusyCounter busy() {
    return busy;
  }

  /**
   * Reports whether the current window is open.
   *
   * @return false once its shell is disposed
   */
  boolean open() {
    return !window.shell().isDisposed();
  }

  /**
   * Reports whether the window's controller shuts down.
   *
   * @return true once it does
   */
  boolean closing() {
    return controller != null && controller.isShuttingDown();
  }

  /**
   * Replaces the window with one built over the same controller, carrying the {@link RebuildState},
   * and disposes the old window last. Does nothing to a window whose controller shuts down.
   */
  void rebuild() {
    MainWindow old = window;
    if (old.shell().isDisposed() || controller.isShuttingDown()) {
      return;
    }
    RebuildState state = new RebuildState(old, controller);
    old.detach();
    MainWindow built =
        new MainWindow(old.shell().getDisplay(), controller.keymap(), controller.theme().palette());
    window = built;
    view = built;
    cursor = new WindowBusyCursor(built.shell());
    built.attach(controller);
    built.onCloseRequest(controller::closeWindow);
    built.setTitle(old.shell().getText());
    state.place(built);
    for (DocumentController document : controller.documents()) {
      built.documentOpened(document);
    }
    built.activeDocumentChanged(controller.activeDocument());
    state.open(built);
    if (busy.active() > 0) {
      cursor.busyChanged(true);
    }
    old.shell().dispose();
  }

  @Override
  public void setActive() {
    busy.setActive();
  }

  @Override
  public void setIdle() {
    busy.setIdle();
  }

  @Override
  public void setTitle(String title) {
    view.setTitle(title);
  }

  @Override
  public void hide() {
    view.hide();
  }

  @Override
  public void dispose() {
    view.dispose();
  }

  @Override
  public void askLineNumber(long lineCount, LineNumberListener listener) {
    view.askLineNumber(lineCount, listener);
  }

  @Override
  public void focusSearch() {
    view.focusSearch();
  }

  @Override
  public void focusQuickFind() {
    view.focusQuickFind();
  }

  @Override
  public void focusView(boolean filtered) {
    view.focusView(filtered);
  }

  @Override
  public void showOverview(boolean shown) {
    view.showOverview(shown);
  }

  @Override
  public void showHighlightersDialog() {
    view.showHighlightersDialog();
  }

  @Override
  public void askFilterName(String suggested, NameListener listener) {
    view.askFilterName(suggested, listener);
  }

  @Override
  public void resizeMainView(int deltaPercent) {
    view.resizeMainView(deltaPercent);
  }

  @Override
  public int splitPercent() {
    return view.splitPercent();
  }

  @Override
  public void showHistoryDialog() {
    view.showHistoryDialog();
  }

  @Override
  public void showFiltersDialog() {
    view.showFiltersDialog();
  }

  @Override
  public void showKeyboardShortcuts() {
    view.showKeyboardShortcuts();
  }

  @Override
  public void showText(String title, String text) {
    view.showText(title, text);
  }

  @Override
  public void showAbout(AboutInfo info) {
    view.showAbout(info);
  }

  @Override
  public void showOpenedFiles() {
    view.showOpenedFiles();
  }

  @Override
  public void askMarkComment(long line, String current, NameListener listener) {
    view.askMarkComment(line, current, listener);
  }

  @Override
  public void showMarks() {
    view.showMarks();
  }

  @Override
  public void askTabName(String current, NameListener listener) {
    view.askTabName(current, listener);
  }

  @Override
  public boolean launch(Path target) {
    return view.launch(target);
  }

  @Override
  public TextLaunch launchAsText(Path file) {
    return view.launchAsText(file);
  }

  @Override
  public OsTraits system() {
    return view.system();
  }

  @Override
  public String toolkitDescription() {
    return view.toolkitDescription();
  }

  @Override
  public void showScratchpad(String text, boolean replace) {
    view.showScratchpad(text, replace);
  }

  @Override
  public void showMultiSearch() {
    view.showMultiSearch();
  }

  @Override
  public void showFolderSearch() {
    view.showFolderSearch();
  }

  @Override
  public void showSelectorsDialog() {
    view.showSelectorsDialog();
  }

  @Override
  public void showPreferences() {
    view.showPreferences();
  }

  @Override
  public int platformFontSize() {
    return view.platformFontSize();
  }

  @Override
  public WindowGeometry.Request windowBounds() {
    return view.windowBounds();
  }

  @Override
  public boolean windowMaximized() {
    return view.windowMaximized();
  }

  @Override
  public void toFront() {
    view.toFront();
  }

  @Override
  public void showWarningMarker(boolean shown) {
    view.showWarningMarker(shown);
  }

  @Override
  public void showLog(String text) {
    view.showLog(text);
  }

  @Override
  public boolean launchUrl(String url) {
    return view.launchUrl(url);
  }

  @Override
  public boolean showGuide(Path file) {
    return view.showGuide(file);
  }

  @Override
  public void askGoToTime(String initial, ZoneId zone, LocalDate caretDate, TimeListener listener) {
    view.askGoToTime(initial, zone, caretDate, listener);
  }

  @Override
  public void askTimeOffset(
      long offset, long caret, int caretDigits, ZoneId zone, TimeListener listener) {
    view.askTimeOffset(offset, caret, caretDigits, zone, listener);
  }

  @Override
  public void askTimeZone(String current, ZoneId defaultZone, NameListener listener) {
    view.askTimeZone(current, defaultZone, listener);
  }

  @Override
  public void showTimestampFormats(
      List<UserFormat> formats,
      String caretLine,
      TimeContext context,
      ZoneId zone,
      FormatsListener listener) {
    view.showTimestampFormats(formats, caretLine, context, zone, listener);
  }

  @Override
  public void showCommandPalette() {
    view.showCommandPalette();
  }

  @Override
  public boolean onTopSupported() {
    return view.onTopSupported();
  }

  @Override
  public boolean minimizeSupported() {
    return view.minimizeSupported();
  }

  @Override
  public void minimize() {
    view.minimize();
  }

  @Override
  public void zoom() {
    view.zoom();
  }

  @Override
  public void setOnTop(boolean onTop) {
    view.setOnTop(onTop);
  }

  @Override
  public boolean fullScreen() {
    return view.fullScreen();
  }

  @Override
  public void setFullScreen(boolean on) {
    view.setFullScreen(on);
  }

  @Override
  public List<Path> chooseFilesToOpen(Path initialDirectory) {
    return view.chooseFilesToOpen(initialDirectory);
  }

  @Override
  public Path chooseSaveFile(String suggestedName) {
    return view.chooseSaveFile(suggestedName);
  }

  @Override
  public void setText(String text) {
    view.setText(text);
  }

  @Override
  public String clipboardText() {
    return view.clipboardText();
  }

  @Override
  public void showError(String title, String message) {
    view.showError(title, message);
  }

  @Override
  public void showInfo(String title, String message) {
    view.showInfo(title, message);
  }

  @Override
  public void showNotice(String message) {
    view.showNotice(message);
  }
}
