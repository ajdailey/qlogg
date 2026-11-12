package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.ViewOptions;
import io.github.ajdailey.qlogg.app.highlight.HighlightSelector;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.MainView;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.text.LogText;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The application's view features beyond the core commands: text wrap, whitespace markers, hidden
 * ANSI colors, folding, block selection, save to file, the scratchpad, search across open files and
 * automatic highlighter sets. Confined to the UI thread.
 *
 * <p>A new document gets {@code view.wrapOnOpen}, {@code view.showWhitespace}, {@code
 * view.hideAnsi} and the fold rules. The {@link HighlightSelector}s are tried in order: a file-name
 * glob on the UI thread, a first-line pattern on a {@code qlogg-read-} worker over at most {@link
 * #FIRST_LINE_BYTES} decoded bytes; the first match picks the document's set. A save target is
 * validated by {@link SafePaths#logFile} on a {@code qlogg-lifecycle-} worker; a target whose real
 * path is an open document's {@link LogDocument#realPath} is refused, so QLogg never writes a file
 * it shows.
 */
final class ViewFeatures {
  /** Bytes read for a first-line selector. */
  static final int FIRST_LINE_BYTES = 4096;

  private static final Logger LOG = LoggerFactory.getLogger(ViewFeatures.class);

  private final ApplicationController application;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final MultiSearchController multiSearch = new MultiSearchController();
  // Search in a folder.
  private final FolderSearchController folderSearch;
  private boolean blockMode;

  ViewFeatures(ApplicationController application, Workers workers, UiDispatcher ui, MainView view) {
    this.application = application;
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.folderSearch = new FolderSearchController(application, ui);
  }

  /** The search across open files. */
  MultiSearchController multiSearch() {
    return multiSearch;
  }

  /** The search in a folder. */
  FolderSearchController folderSearch() {
    return folderSearch;
  }

  /** Reports whether a plain drag selects a block. */
  boolean blockMode() {
    return blockMode;
  }

  /** Applies the view settings to a new document and picks its highlighter set. */
  void configure(DocumentController document) {
    ViewOptions options = application.preferences().view();
    document.document().setHideAnsi(options.hideAnsi());
    document.setWrapBoth(options.wrapOnOpen());
    document.setWhitespaceBoth(options.showWhitespace());
    document.setFoldRules(options.folds());
    chooseHighlightSet(document, options.selectors());
  }

  /** Drops a closed document's group of the search across files. */
  void documentClosed(DocumentController document) {
    multiSearch.documentClosed(document);
  }

  /**
   * Runs a command of these features.
   *
   * @return false when the command is not one of them
   */
  boolean execute(CommandId command) {
    DocumentController document = application.activeDocument();
    ViewOptions options = application.preferences().view();
    switch (command) {
      case WRAP_ON_OPEN -> setView(options.withWrapOnOpen(!options.wrapOnOpen()));
      case TOGGLE_WHITESPACE -> {
        boolean on = !options.showWhitespace();
        setView(options.withShowWhitespace(on));
        for (DocumentController open : application.documents()) {
          open.setWhitespaceBoth(on);
        }
      }
      case HIDE_ANSI -> {
        boolean on = !options.hideAnsi();
        setView(options.withHideAnsi(on));
        for (DocumentController open : application.documents()) {
          open.document().setHideAnsi(on);
          open.changed();
          open.filtered().changed();
        }
      }
      case BLOCK_SELECTION -> blockMode = !blockMode;
      case SCRATCHPAD -> view.showScratchpad(null, false);
      case AUTO_HIGHLIGHTERS -> view.showSelectorsDialog();
      case SEND_TO_SCRATCHPAD -> sendToScratchpad(false);
      case REPLACE_SCRATCHPAD -> sendToScratchpad(true);
      case SEARCH_ALL_FILES -> searchAllFiles();
      case SEARCH_FOLDER -> view.showFolderSearch();
      case SAVE_SELECTION -> save(false);
      case SAVE_FILTERED -> save(true);
      case TOGGLE_WRAP, TOGGLE_FOLD, COLLAPSE_ALL_FOLDS, EXPAND_ALL_FOLDS -> {
        if (document != null) {
          documentCommand(command, document);
        }
      }
      default -> {
        return false;
      }
    }
    return true;
  }

  private void documentCommand(CommandId command, DocumentController document) {
    switch (command) {
      case TOGGLE_WRAP -> document.setWrapBoth(!document.wrap());
      case TOGGLE_FOLD -> document.toggleFold();
      case COLLAPSE_ALL_FOLDS -> document.collapseAllFolds();
      default -> document.expandAllFolds();
    }
  }

  /**
   * Replaces the view settings and saves them: new selectors apply to files opened later, new fold
   * rules to the open files of every window, and a new color scheme to every window's views.
   *
   * @param options the settings
   */
  void setView(ViewOptions options) {
    Preferences current = application.preferences();
    application.setPreferences(current.withView(options));
    if (!options.folds().equals(current.view().folds())) {
      for (ApplicationController window : application.windowSet().windows()) {
        for (DocumentController open : window.documents()) {
          open.setFoldRules(options.folds());
        }
      }
    }
    // View colors.
    if (!options.colors().equals(current.view().colors())) {
      application.theme().setColors(options.colors());
    }
  }

  private void sendToScratchpad(final boolean replace) {
    LineViewController target = application.focusedView();
    if (target == null) {
      return;
    }
    target.selectionText(
        new TextListener() {
          @Override
          public void textRead(String text) {
            view.showScratchpad(text, replace);
          }
        });
  }

  private void searchAllFiles() {
    DocumentController document = application.activeDocument();
    if (document == null) {
      return;
    }
    String text = document.search().fieldText();
    if (text.isEmpty()) {
      view.showNotice(UiText.MULTI_NO_TEXT.text());
      return;
    }
    multiSearch.run(application.documents(), text, document.search().options());
    view.showMultiSearch();
  }

  /** Asks for a target, validates it on a worker, then saves the selection or the filtered view. */
  private void save(final boolean filtered) {
    final DocumentController document = application.activeDocument();
    if (document == null) {
      return;
    }
    final LineViewController target = filtered ? document.filtered() : application.focusedView();
    if (target == null) {
      return;
    }
    Path chosen = view.chooseSaveFile(document.state().title() + ".txt");
    if (chosen == null) {
      return;
    }
    final Path given = chosen;
    boolean started =
        workers.tryStart(
            Workers.Kind.LIFECYCLE,
            new Runnable() {
              @Override
              public void run() {
                validate(given, target, filtered);
              }
            });
    if (!started) {
      LOG.debug("Save not started: workers are closed");
    }
  }

  @WorkerThread
  private void validate(Path given, final LineViewController target, final boolean filtered) {
    Path checked = null;
    String error = null;
    try {
      checked = SafePaths.logFile(given);
    } catch (IOException e) {
      error = UiText.SAVE_FAILED.format(LogText.oneLine(e.getMessage()));
    }
    final Path file = checked;
    final String message = error;
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            validated(file, message, target, filtered);
          }
        });
  }

  private void validated(Path file, String error, LineViewController target, boolean filtered) {
    if (target.isClosed()) {
      return;
    }
    if (error != null) {
      view.showError(UiText.SAVE_TITLE.text(), error);
      return;
    }
    for (ApplicationController window : application.windowSet().windows()) {
      for (DocumentController open : window.documents()) {
        if (!open.document().inMemory() && open.document().realPath().equals(file)) {
          view.showError(UiText.SAVE_TITLE.text(), UiText.SAVE_OPEN_FILE.text());
          return;
        }
      }
    }
    if (filtered) {
      target.saveAll(file);
    } else {
      target.saveLines(file);
    }
  }

  /** Tries the selectors in order; the first first-line selector moves the rest to a worker. */
  private void chooseHighlightSet(
      final DocumentController document, final List<HighlightSelector> selectors) {
    LogDocument log = document.document();
    String name = fileName(log);
    for (int i = 0; i < selectors.size(); i++) {
      HighlightSelector selector = selectors.get(i);
      if (selector.byFileName()) {
        if (selector.matchesName(name)) {
          document.setAutoHighlightSet(selector.setId());
          return;
        }
        continue;
      }
      final int from = i;
      boolean started =
          workers.tryStart(
              Workers.Kind.READ,
              new Runnable() {
                @Override
                public void run() {
                  matchFirstLine(document, selectors, from);
                }
              });
      if (!started) {
        LOG.debug("Highlighter selection not run: workers are closed");
      }
      return;
    }
  }

  /** Matches the remaining selectors against the file name and first line. */
  @WorkerThread
  private void matchFirstLine(
      final DocumentController document, List<HighlightSelector> selectors, int from) {
    LogDocument log = document.document();
    String chosen = null;
    if (log.enter()) {
      try {
        String first = firstLine(log);
        String name = fileName(log);
        for (int i = from; i < selectors.size() && chosen == null; i++) {
          HighlightSelector selector = selectors.get(i);
          if (selector.byFileName()
              ? selector.matchesName(name)
              : selector.matchesFirstLine(first)) {
            chosen = selector.setId();
          }
        }
      } catch (IOException e) {
        LOG.debug(
            "First line of {} not read: {}",
            LogText.oneLine(log.path()),
            LogText.oneLine(e.getMessage()));
      } finally {
        log.exit();
      }
    }
    final String set = chosen;
    if (set == null) {
      return;
    }
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (!document.isClosed()) {
              document.setAutoHighlightSet(set);
            }
          }
        });
  }

  private static String fileName(LogDocument log) {
    Path name = log.inMemory() ? null : log.path().getFileName();
    return name == null ? "" : name.toString();
  }

  /** The first line of the file, from at most {@link #FIRST_LINE_BYTES}, decoded, BOM dropped. */
  @WorkerThread
  static String firstLine(LogDocument log) throws IOException {
    ByteBuffer bytes = ByteBuffer.allocate(FIRST_LINE_BYTES);
    int read = log.bytes().read(0, bytes);
    bytes.flip();
    bytes.limit(Math.max(0, read));
    CharsetDecoder decoder =
        log.encoding()
            .charset()
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    CharBuffer chars = decoder.decode(bytes);
    String text = chars.toString();
    if (text.startsWith("﻿")) {
      text = text.substring(1);
    }
    int end = text.indexOf('\n');
    if (end >= 0) {
      text = text.substring(0, end);
    }
    return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
  }
}
