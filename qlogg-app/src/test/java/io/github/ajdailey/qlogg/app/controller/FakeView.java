package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.about.AboutInfo;
import io.github.ajdailey.qlogg.app.config.WindowState;
import io.github.ajdailey.qlogg.app.view.MainView;
import io.github.ajdailey.qlogg.app.view.UiThreadCheck;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import io.github.ajdailey.qlogg.core.time.UserFormat;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Records what the controllers do to the window. Used on the test thread, the UI thread here,
 * except the activity count, which workers change. A call from another thread, or any call after
 * {@link #dispose} other than the activity count, is recorded and thrown by {@link
 * #assertConfined}.
 */
final class FakeView implements MainView {
  private final UiThreadCheck ui = new UiThreadCheck();
  private final AtomicReference<AssertionError> afterDispose =
      new AtomicReference<AssertionError>();
  String title = "QLogg";
  boolean hidden;
  boolean disposed;
  String clipboard;
  final List<String> errors = new ArrayList<String>();
  final List<String> infos = new ArrayList<String>();
  final List<String> texts = new ArrayList<String>();
  final List<String> notices = new ArrayList<String>();
  int quickFindFocused;
  int split = 70;
  int historyDialogs;
  int filterDialogs;
  final AtomicInteger active = new AtomicInteger();
  final AtomicInteger activations = new AtomicInteger();
  AboutInfo about;
  boolean shortcuts;
  int searchFocused;
  List<Path> chosen = List.of();
  long askedLineCount = -1;
  LineNumberListener lineListener;
  String focusedView;
  boolean overview = true;
  int highlighterDialogs;
  String suggestedName;
  NameListener nameListener;
  int openedFilesShown;
  final List<Path> launched = new ArrayList<Path>();
  boolean launchResult = true;
  int preferencesShown;
  WindowState window = new WindowState(10, 20, 800, 600, false);

  @Override
  public void setTitle(String title) {
    check("setTitle");
    this.title = title;
  }

  @Override
  public void hide() {
    check("hide");
    hidden = true;
  }

  @Override
  public void dispose() {
    check("dispose");
    disposed = true;
  }

  @Override
  public void setActive() {
    active.incrementAndGet();
    activations.incrementAndGet();
  }

  @Override
  public void setIdle() {
    active.decrementAndGet();
  }

  @Override
  public void askLineNumber(long lineCount, LineNumberListener listener) {
    check("askLineNumber");
    askedLineCount = lineCount;
    lineListener = listener;
  }

  @Override
  public void focusSearch() {
    check("focusSearch");
    searchFocused++;
  }

  @Override
  public void focusQuickFind() {
    check("focusQuickFind");
    quickFindFocused++;
  }

  @Override
  public void resizeMainView(int deltaPercent) {
    check("resizeMainView");
    split += deltaPercent;
  }

  @Override
  public int splitPercent() {
    check("splitPercent");
    return split;
  }

  @Override
  public void showHistoryDialog() {
    check("showHistoryDialog");
    historyDialogs++;
  }

  @Override
  public void showFiltersDialog() {
    check("showFiltersDialog");
    filterDialogs++;
  }

  @Override
  public void showNotice(String message) {
    check("showNotice");
    notices.add(message);
  }

  /** Throws the first {@code MainView} call made off the UI thread or after {@link #dispose}. */
  void assertConfined() {
    ui.assertConfined();
    AssertionError error = afterDispose.get();
    if (error != null) {
      throw error;
    }
  }

  private void check(String call) {
    ui.check(call);
    if (disposed) {
      afterDispose.compareAndSet(null, new AssertionError(call + " ran after dispose"));
    }
  }

  /** Returns the last notice, or null. */
  String lastNotice() {
    return notices.isEmpty() ? null : notices.get(notices.size() - 1);
  }

  @Override
  public void focusView(boolean filtered) {
    check("focusView");
    focusedView = filtered ? "filtered" : "main";
  }

  @Override
  public void showOverview(boolean shown) {
    check("showOverview");
    overview = shown;
  }

  @Override
  public void showHighlightersDialog() {
    check("showHighlightersDialog");
    highlighterDialogs++;
  }

  @Override
  public void askFilterName(String suggested, NameListener listener) {
    check("askFilterName");
    suggestedName = suggested;
    nameListener = listener;
  }

  @Override
  public void showKeyboardShortcuts() {
    check("showKeyboardShortcuts");
    shortcuts = true;
  }

  @Override
  public void showText(String title, String text) {
    check("showText");
    texts.add(title + ": " + text);
  }

  @Override
  public void showAbout(AboutInfo info) {
    check("showAbout");
    about = info;
  }

  @Override
  public String toolkitDescription() {
    check("toolkitDescription");
    return "Fake toolkit";
  }

  @Override
  public List<Path> chooseFilesToOpen(Path initialDirectory) {
    check("chooseFilesToOpen");
    return chosen;
  }

  @Override
  public void setText(String text) {
    check("setText");
    clipboard = text;
  }

  @Override
  public String clipboardText() {
    check("clipboardText");
    return clipboard;
  }

  @Override
  public void showError(String title, String message) {
    check("showError");
    errors.add(title + ": " + message);
  }

  @Override
  public void showInfo(String title, String message) {
    check("showInfo");
    infos.add(title + ": " + message);
  }

  @Override
  public void showOpenedFiles() {
    check("showOpenedFiles");
    openedFilesShown++;
  }

  // Mark comments and marks list.
  long commentLine = -1;
  int marksShown;

  @Override
  public void askMarkComment(long line, String current, NameListener listener) {
    check("askMarkComment");
    commentLine = line;
    suggestedName = current;
    nameListener = listener;
  }

  @Override
  public void showMarks() {
    check("showMarks");
    marksShown++;
  }

  @Override
  public void askTabName(String current, NameListener listener) {
    check("askTabName");
    suggestedName = current;
    nameListener = listener;
  }

  @Override
  public boolean launch(Path target) {
    check("launch");
    launched.add(target);
    return launchResult;
  }

  // Matches the host, as MainWindow does: Files.isExecutable is true for a .log file on Windows.
  boolean byExtension = System.getProperty("os.name", "").startsWith("Windows");

  @Override
  public boolean launchesByExtension() {
    check("launchesByExtension");
    return byExtension;
  }

  // Save, scratchpad, search across open files and automatic highlighter sets.
  Path saveTarget;
  final List<String> scratchpad = new ArrayList<String>();
  int multiSearchShown;

  @Override
  public Path chooseSaveFile(String suggestedName) {
    check("chooseSaveFile");
    return saveTarget;
  }

  @Override
  public void showScratchpad(String text, boolean replace) {
    check("showScratchpad");
    if (text != null) {
      if (replace) {
        scratchpad.clear();
      }
      scratchpad.add(text);
    }
  }

  @Override
  public void showMultiSearch() {
    check("showMultiSearch");
    multiSearchShown++;
  }

  // Search in a folder.

  int folderSearchShown;

  @Override
  public void showFolderSearch() {
    check("showFolderSearch");
    folderSearchShown++;
  }

  @Override
  public void showSelectorsDialog() {
    check("showSelectorsDialog");
  }

  @Override
  public void showPreferences() {
    check("showPreferences");
    preferencesShown++;
  }

  @Override
  public int platformFontSize() {
    check("platformFontSize");
    return 10;
  }

  @Override
  public WindowGeometry.Request windowBounds() {
    check("windowBounds");
    return new WindowGeometry.Request(window.x(), window.y(), window.width(), window.height());
  }

  @Override
  public boolean windowMaximized() {
    check("windowMaximized");
    return window.maximized();
  }

  // Multiple windows, logging and support items.
  int raised;
  Boolean warningMarker;
  final List<String> logs = new ArrayList<String>();
  final List<String> urls = new ArrayList<String>();

  @Override
  public void toFront() {
    check("toFront");
    raised++;
  }

  @Override
  public void showWarningMarker(boolean shown) {
    check("showWarningMarker");
    warningMarker = shown;
  }

  @Override
  public void showLog(String text) {
    check("showLog");
    logs.add(text);
  }

  @Override
  public boolean launchUrl(String url) {
    check("launchUrl");
    urls.add(url);
    return launchResult;
  }

  // User guide.
  final List<Path> guides = new ArrayList<Path>();
  boolean browser;

  @Override
  public boolean showGuide(Path file) {
    check("showGuide");
    guides.add(file);
    return browser;
  }

  // Timestamps.
  String timeInitial;
  ZoneId timeZone;
  LocalDate caretDate;
  TimeListener timeListener;
  long askedOffset;
  long askedCaret;
  String zoneCurrent;
  List<UserFormat> formatsShown;
  String formatSample;
  FormatsListener formatsListener;

  @Override
  public void askGoToTime(String initial, ZoneId zone, LocalDate date, TimeListener listener) {
    check("askGoToTime");
    timeInitial = initial;
    timeZone = zone;
    caretDate = date;
    timeListener = listener;
  }

  @Override
  public void askTimeOffset(
      long offset, long caret, int caretDigits, ZoneId zone, TimeListener listener) {
    check("askTimeOffset");
    askedOffset = offset;
    askedCaret = caret;
    timeZone = zone;
    timeListener = listener;
  }

  @Override
  public void askTimeZone(String current, ZoneId defaultZone, NameListener listener) {
    check("askTimeZone");
    zoneCurrent = current;
    nameListener = listener;
  }

  @Override
  public void showTimestampFormats(
      List<UserFormat> formats,
      String caretLine,
      TimeContext context,
      ZoneId zone,
      FormatsListener listener) {
    check("showTimestampFormats");
    formatsShown = formats;
    formatSample = caretLine;
    formatsListener = listener;
  }

  // Command palette.

  int paletteShown;

  @Override
  public void showCommandPalette() {
    check("showCommandPalette");
    paletteShown++;
  }

  // Always on Top.

  /** What {@link #onTopSupported} reports. */
  boolean onTopSupported = true;

  /** Each {@link #setOnTop} argument, in order. */
  final List<Boolean> onTopCalls = new ArrayList<Boolean>();

  @Override
  public boolean onTopSupported() {
    check("onTopSupported");
    return onTopSupported;
  }

  @Override
  public void setOnTop(boolean onTop) {
    check("setOnTop");
    onTopCalls.add(onTop);
  }
}
