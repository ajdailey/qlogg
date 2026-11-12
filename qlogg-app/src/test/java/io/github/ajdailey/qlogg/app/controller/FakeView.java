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

/**
 * Records what the controllers do to the window. Used on the test thread, the UI thread here,
 * except the activity count, which workers change. A call from another thread is recorded and
 * thrown by {@link #assertConfined}.
 */
final class FakeView implements MainView {
  private final UiThreadCheck ui = new UiThreadCheck();
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
    ui.check("setTitle");
    this.title = title;
  }

  @Override
  public void hide() {
    ui.check("hide");
    hidden = true;
  }

  @Override
  public void dispose() {
    ui.check("dispose");
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
    ui.check("askLineNumber");
    askedLineCount = lineCount;
    lineListener = listener;
  }

  @Override
  public void focusSearch() {
    ui.check("focusSearch");
    searchFocused++;
  }

  @Override
  public void focusQuickFind() {
    ui.check("focusQuickFind");
    quickFindFocused++;
  }

  @Override
  public void resizeMainView(int deltaPercent) {
    ui.check("resizeMainView");
    split += deltaPercent;
  }

  @Override
  public int splitPercent() {
    ui.check("splitPercent");
    return split;
  }

  @Override
  public void showHistoryDialog() {
    ui.check("showHistoryDialog");
    historyDialogs++;
  }

  @Override
  public void showFiltersDialog() {
    ui.check("showFiltersDialog");
    filterDialogs++;
  }

  @Override
  public void showNotice(String message) {
    ui.check("showNotice");
    notices.add(message);
  }

  /** Throws the first {@code MainView} call made off the UI thread. */
  void assertConfined() {
    ui.assertConfined();
  }

  /** Returns the last notice, or null. */
  String lastNotice() {
    return notices.isEmpty() ? null : notices.get(notices.size() - 1);
  }

  @Override
  public void focusView(boolean filtered) {
    ui.check("focusView");
    focusedView = filtered ? "filtered" : "main";
  }

  @Override
  public void showOverview(boolean shown) {
    ui.check("showOverview");
    overview = shown;
  }

  @Override
  public void showHighlightersDialog() {
    ui.check("showHighlightersDialog");
    highlighterDialogs++;
  }

  @Override
  public void askFilterName(String suggested, NameListener listener) {
    ui.check("askFilterName");
    suggestedName = suggested;
    nameListener = listener;
  }

  @Override
  public void showKeyboardShortcuts() {
    ui.check("showKeyboardShortcuts");
    shortcuts = true;
  }

  @Override
  public void showText(String title, String text) {
    ui.check("showText");
    texts.add(title + ": " + text);
  }

  @Override
  public void showAbout(AboutInfo info) {
    ui.check("showAbout");
    about = info;
  }

  @Override
  public String toolkitDescription() {
    ui.check("toolkitDescription");
    return "Fake toolkit";
  }

  @Override
  public List<Path> chooseFilesToOpen(Path initialDirectory) {
    ui.check("chooseFilesToOpen");
    return chosen;
  }

  @Override
  public void setText(String text) {
    ui.check("setText");
    clipboard = text;
  }

  @Override
  public String clipboardText() {
    ui.check("clipboardText");
    return clipboard;
  }

  @Override
  public void showError(String title, String message) {
    ui.check("showError");
    errors.add(title + ": " + message);
  }

  @Override
  public void showInfo(String title, String message) {
    ui.check("showInfo");
    infos.add(title + ": " + message);
  }

  @Override
  public void showOpenedFiles() {
    ui.check("showOpenedFiles");
    openedFilesShown++;
  }

  // Mark comments and marks list.
  long commentLine = -1;
  int marksShown;

  @Override
  public void askMarkComment(long line, String current, NameListener listener) {
    ui.check("askMarkComment");
    commentLine = line;
    suggestedName = current;
    nameListener = listener;
  }

  @Override
  public void showMarks() {
    ui.check("showMarks");
    marksShown++;
  }

  @Override
  public void askTabName(String current, NameListener listener) {
    ui.check("askTabName");
    suggestedName = current;
    nameListener = listener;
  }

  @Override
  public boolean launch(Path target) {
    ui.check("launch");
    launched.add(target);
    return launchResult;
  }

  // Matches the host, as MainWindow does: Files.isExecutable is true for a .log file on Windows.
  boolean byExtension = System.getProperty("os.name", "").startsWith("Windows");

  @Override
  public boolean launchesByExtension() {
    ui.check("launchesByExtension");
    return byExtension;
  }

  // Save, scratchpad, search across open files and automatic highlighter sets.
  Path saveTarget;
  final List<String> scratchpad = new ArrayList<String>();
  int multiSearchShown;

  @Override
  public Path chooseSaveFile(String suggestedName) {
    ui.check("chooseSaveFile");
    return saveTarget;
  }

  @Override
  public void showScratchpad(String text, boolean replace) {
    ui.check("showScratchpad");
    if (text != null) {
      if (replace) {
        scratchpad.clear();
      }
      scratchpad.add(text);
    }
  }

  @Override
  public void showMultiSearch() {
    ui.check("showMultiSearch");
    multiSearchShown++;
  }

  // Search in a folder.

  int folderSearchShown;

  @Override
  public void showFolderSearch() {
    ui.check("showFolderSearch");
    folderSearchShown++;
  }

  @Override
  public void showSelectorsDialog() {
    ui.check("showSelectorsDialog");
  }

  @Override
  public void showPreferences() {
    ui.check("showPreferences");
    preferencesShown++;
  }

  @Override
  public int platformFontSize() {
    ui.check("platformFontSize");
    return 10;
  }

  @Override
  public WindowGeometry.Request windowBounds() {
    ui.check("windowBounds");
    return new WindowGeometry.Request(window.x(), window.y(), window.width(), window.height());
  }

  @Override
  public boolean windowMaximized() {
    ui.check("windowMaximized");
    return window.maximized();
  }

  // Multiple windows, logging and support items.
  int raised;
  Boolean warningMarker;
  final List<String> logs = new ArrayList<String>();
  final List<String> urls = new ArrayList<String>();

  @Override
  public void toFront() {
    ui.check("toFront");
    raised++;
  }

  @Override
  public void showWarningMarker(boolean shown) {
    ui.check("showWarningMarker");
    warningMarker = shown;
  }

  @Override
  public void showLog(String text) {
    ui.check("showLog");
    logs.add(text);
  }

  @Override
  public boolean launchUrl(String url) {
    ui.check("launchUrl");
    urls.add(url);
    return launchResult;
  }

  // User guide.
  final List<Path> guides = new ArrayList<Path>();
  boolean browser;

  @Override
  public boolean showGuide(Path file) {
    ui.check("showGuide");
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
    ui.check("askGoToTime");
    timeInitial = initial;
    timeZone = zone;
    caretDate = date;
    timeListener = listener;
  }

  @Override
  public void askTimeOffset(
      long offset, long caret, int caretDigits, ZoneId zone, TimeListener listener) {
    ui.check("askTimeOffset");
    askedOffset = offset;
    askedCaret = caret;
    timeZone = zone;
    timeListener = listener;
  }

  @Override
  public void askTimeZone(String current, ZoneId defaultZone, NameListener listener) {
    ui.check("askTimeZone");
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
    ui.check("showTimestampFormats");
    formatsShown = formats;
    formatSample = caretLine;
    formatsListener = listener;
  }

  // Command palette.

  int paletteShown;

  @Override
  public void showCommandPalette() {
    ui.check("showCommandPalette");
    paletteShown++;
  }

  // Always on Top.

  /** What {@link #onTopSupported} reports. */
  boolean onTopSupported = true;

  /** Each {@link #setOnTop} argument, in order. */
  final List<Boolean> onTopCalls = new ArrayList<Boolean>();

  @Override
  public boolean onTopSupported() {
    ui.check("onTopSupported");
    return onTopSupported;
  }

  @Override
  public void setOnTop(boolean onTop) {
    ui.check("setOnTop");
    onTopCalls.add(onTop);
  }
}
