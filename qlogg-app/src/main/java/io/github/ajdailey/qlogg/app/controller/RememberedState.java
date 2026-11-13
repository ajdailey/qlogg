package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SearchToggles;
import io.github.ajdailey.qlogg.app.config.UiState;
import io.github.ajdailey.qlogg.app.config.WindowState;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * One window's writes of remembered state: tab positions to the {@link ConfigStore}'s positions,
 * and the recent files, search toggles, line numbers, Open dialog folder, window placement and
 * dialog sizes to {@link UiState}. A write that changes nothing leaves the settings as they are.
 * Saves go through the host, {@link ApplicationController#SAVE_DELAY_MILLIS} after the last change.
 * Confined to the UI thread.
 */
public final class RememberedState {
  private final RememberedStateHost host;
  private final ConfigStore config;
  private final BooleanSupplier closing;

  /**
   * Creates the remembered state of one window.
   *
   * @param closing true once the window stops accepting operations; no save is scheduled then
   */
  RememberedState(RememberedStateHost host, ConfigStore config, BooleanSupplier closing) {
    this.host = host;
    this.config = config;
    this.closing = closing;
  }

  /** Saves {@link ApplicationController#SAVE_DELAY_MILLIS} after the last call. */
  void scheduleSave() {
    if (!closing.getAsBoolean()) {
      host.scheduleSave();
    }
  }

  /** Records a tab's top line and caret for its file; a pasted document records nothing. */
  void rememberPosition(DocumentController document) {
    if (document.document().inMemory()) {
      return;
    }
    TextPosition caret = document.selection().caret();
    config
        .positions()
        .put(
            document.document().path(),
            document.viewport().topLine(),
            caret.line(),
            caret.column());
  }

  /** Lists an opened file first in Recent Files and drops it from Recently Closed. */
  void rememberRecent(LogDocument document) {
    if (document.inMemory()) {
      return;
    }
    Preferences current = host.settings();
    UiState state =
        current
            .state()
            .addRecent(document.path().toString(), current.options().recentCount())
            .removeClosed(document.path().toString());
    if (!state.equals(current.state())) {
      host.setPreferences(current.withState(state));
    }
  }

  /** Returns the saved search toggles, with In selection off. */
  SearchOptions searchOptions() {
    SearchToggles toggles = host.settings().state().search();
    return new SearchOptions(
        toggles.regex() ? MatchMode.REGEX : MatchMode.LITERAL,
        toggles.caseSensitive(),
        toggles.wholeWord(),
        toggles.inverse(),
        toggles.combining(),
        false,
        toggles.autoRefresh(),
        toggles.keepResults(),
        toggles.range(),
        toggles.rangeEnd(),
        toggles.contextBefore(),
        toggles.contextAfter());
  }

  /** Saves a search's toggles, less In selection, for the next document in any window. */
  void rememberSearchOptions(SearchOptions options) {
    UiState state = host.settings().state();
    SearchToggles toggles =
        new SearchToggles(
            options.mode() == MatchMode.REGEX,
            options.caseSensitive(),
            options.wholeWord(),
            options.inverse(),
            options.combining(),
            options.autoRefresh(),
            options.keepResults(),
            options.range(),
            options.rangeEnd(),
            options.contextBefore(),
            options.contextAfter());
    if (!toggles.equals(state.search())) {
      host.setPreferences(host.settings().withState(state.withSearch(toggles)));
    }
  }

  /** Saves a tab's main and filtered line number toggles for the next document. */
  void rememberLineNumbers(DocumentController document) {
    UiState state = host.settings().state();
    UiState next = state.withLineNumbers(document.lineNumbers(), document.filtered().lineNumbers());
    if (!next.equals(state)) {
      host.setPreferences(host.settings().withState(next));
    }
  }

  /** Saves the folder File > Open chose from. */
  void rememberOpenDirectory(Path parent) {
    UiState state = host.settings().state();
    String directory = parent.toString();
    if (!directory.equals(state.openDirectory())) {
      host.setPreferences(host.settings().withState(state.withOpenDirectory(directory)));
    }
  }

  /** Returns the folder File > Open chose from last, or null when none or it does not parse. */
  Path rememberedDirectory() {
    String text = host.settings().state().openDirectory();
    if (text.isEmpty()) {
      return null;
    }
    try {
      return Path.of(text);
    } catch (InvalidPathException e) {
      return null;
    }
  }

  /**
   * Records a window's placement and split without scheduling a save; window close and quit save.
   *
   * @param window the normal bounds and maximized state
   * @param split the main view's share, clamped to the split range
   */
  void rememberWindow(WindowState window, int split) {
    int percent = Math.max(Preferences.MIN_SPLIT, Math.min(Preferences.MAX_SPLIT, split));
    UiState state = host.settings().state();
    UiState next = state.withWindow(window, percent);
    if (!next.equals(state)) {
      host.preferencesChanged(host.settings().withState(next));
    }
  }

  /**
   * Returns a dialog's remembered size.
   *
   * @param name the dialog's name
   * @return the size, or null
   */
  public UiState.Size dialogSize(String name) {
    return host.settings().state().dialogs().get(name);
  }

  /**
   * Remembers a dialog's size for the next time it opens; a width or height under 1 is ignored.
   *
   * @param name the dialog's name, {@code [A-Za-z0-9]+}
   * @param width pixels
   * @param height pixels
   */
  public void rememberDialogSize(String name, int width, int height) {
    if (width < 1 || height < 1) {
      return;
    }
    UiState.Size size = new UiState.Size(width, height);
    UiState state = host.settings().state();
    if (!size.equals(state.dialogs().get(name))) {
      host.setPreferences(host.settings().withState(state.withDialog(name, size)));
    }
  }
}
