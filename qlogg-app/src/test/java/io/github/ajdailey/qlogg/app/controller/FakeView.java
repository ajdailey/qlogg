package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.about.AboutInfo;
import io.github.ajdailey.qlogg.app.config.WindowState;
import io.github.ajdailey.qlogg.app.view.MainView;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Records what the controllers do to the window. Used on the test thread, the UI thread here,
 * except the activity count, which workers change.
 */
final class FakeView implements MainView {
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
  int optionsShown;
  WindowState window = new WindowState(10, 20, 800, 600, false);

  @Override
  public void setTitle(String title) {
    this.title = title;
  }

  @Override
  public void hide() {
    hidden = true;
  }

  @Override
  public void dispose() {
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
    askedLineCount = lineCount;
    lineListener = listener;
  }

  @Override
  public void focusSearch() {
    searchFocused++;
  }

  @Override
  public void focusQuickFind() {
    quickFindFocused++;
  }

  @Override
  public void resizeMainView(int deltaPercent) {
    split += deltaPercent;
  }

  @Override
  public int splitPercent() {
    return split;
  }

  @Override
  public void showHistoryDialog() {
    historyDialogs++;
  }

  @Override
  public void showFiltersDialog() {
    filterDialogs++;
  }

  @Override
  public void showNotice(String message) {
    notices.add(message);
  }

  /** Returns the last notice, or null. */
  String lastNotice() {
    return notices.isEmpty() ? null : notices.get(notices.size() - 1);
  }

  @Override
  public void focusView(boolean filtered) {
    focusedView = filtered ? "filtered" : "main";
  }

  @Override
  public void showOverview(boolean shown) {
    overview = shown;
  }

  @Override
  public void showHighlightersDialog() {
    highlighterDialogs++;
  }

  @Override
  public void askFilterName(String suggested, NameListener listener) {
    suggestedName = suggested;
    nameListener = listener;
  }

  @Override
  public void showKeyboardShortcuts() {
    shortcuts = true;
  }

  @Override
  public void showText(String title, String text) {
    texts.add(title + ": " + text);
  }

  @Override
  public void showAbout(AboutInfo info) {
    about = info;
  }

  @Override
  public String toolkitDescription() {
    return "Fake toolkit";
  }

  @Override
  public List<Path> chooseFilesToOpen(Path initialDirectory) {
    return chosen;
  }

  @Override
  public void setText(String text) {
    clipboard = text;
  }

  @Override
  public String clipboardText() {
    return clipboard;
  }

  @Override
  public void showError(String title, String message) {
    errors.add(title + ": " + message);
  }

  @Override
  public void showInfo(String title, String message) {
    infos.add(title + ": " + message);
  }

  @Override
  public void showOpenedFiles() {
    openedFilesShown++;
  }

  @Override
  public void askTabName(String current, NameListener listener) {
    suggestedName = current;
    nameListener = listener;
  }

  @Override
  public boolean launch(Path target) {
    launched.add(target);
    return launchResult;
  }

  // Save, scratchpad, search across open files and automatic highlighter sets.
  Path saveTarget;
  final List<String> scratchpad = new ArrayList<String>();
  int multiSearchShown;

  @Override
  public Path chooseSaveFile(String suggestedName) {
    return saveTarget;
  }

  @Override
  public void showScratchpad(String text, boolean replace) {
    if (text != null) {
      if (replace) {
        scratchpad.clear();
      }
      scratchpad.add(text);
    }
  }

  @Override
  public void showMultiSearch() {
    multiSearchShown++;
  }

  @Override
  public void showSelectorsDialog() {}

  @Override
  public void showOptions() {
    optionsShown++;
  }

  @Override
  public int platformFontSize() {
    return 10;
  }

  @Override
  public WindowGeometry.Request windowBounds() {
    return new WindowGeometry.Request(window.x(), window.y(), window.width(), window.height());
  }

  @Override
  public boolean windowMaximized() {
    return window.maximized();
  }

  // Multiple windows, logging and support items.
  int raised;
  Boolean warningMarker;
  final List<String> logs = new ArrayList<String>();
  final List<String> urls = new ArrayList<String>();

  @Override
  public void toFront() {
    raised++;
  }

  @Override
  public void showWarningMarker(boolean shown) {
    warningMarker = shown;
  }

  @Override
  public void showLog(String text) {
    logs.add(text);
  }

  @Override
  public boolean launchUrl(String url) {
    urls.add(url);
    return launchResult;
  }

  // User guide.
  final List<Path> guides = new ArrayList<Path>();
  boolean browser;

  @Override
  public boolean showGuide(Path file) {
    guides.add(file);
    return browser;
  }
}
