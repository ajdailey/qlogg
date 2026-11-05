package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.highlight.HighlightSettings;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.TimeText;
import io.github.ajdailey.qlogg.core.model.HighlightSet;
import io.github.ajdailey.qlogg.core.model.Visibility;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.time.TimestampFormat;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The checked and enabled state of each command's menu item, and the entries of the menus filled
 * from the controllers, read from an {@link ApplicationController} on every call. Call on the UI
 * thread.
 *
 * <p>A check command in {@link #APPLICATION_CHECKS} reads the settings and is always enabled; any
 * other check command reads the shown document and is enabled while one shows. The commands in
 * {@link #FILE_COMMANDS} need a document with a file, so they are disabled for clipboard text.
 */
public final class CommandStates {
  /** Check commands whose state does not depend on a document. */
  public static final Set<CommandId> APPLICATION_CHECKS =
      EnumSet.of(
          CommandId.RUN_SEARCH_ON_ADD,
          CommandId.FOLLOW_ON_LOAD,
          CommandId.FOLLOW_ON_OVERSCROLL,
          CommandId.TOGGLE_OVERVIEW,
          CommandId.TOGGLE_MATCH_HIGHLIGHT,
          CommandId.THEME_LIGHT,
          CommandId.THEME_DARK,
          CommandId.THEME_SYSTEM,
          CommandId.NO_HIGHLIGHTERS,
          CommandId.WRAP_ON_OPEN,
          CommandId.TOGGLE_WHITESPACE,
          CommandId.HIDE_ANSI,
          CommandId.BLOCK_SELECTION,
          CommandId.DEBUG_LOG_FILE);

  /** Commands that need a document with a file. */
  public static final Set<CommandId> FILE_COMMANDS =
      EnumSet.of(
          CommandId.RELOAD,
          CommandId.COPY_PATH,
          CommandId.COPY_FILE_NAME,
          CommandId.OPEN_FOLDER,
          CommandId.OPEN_IN_EDITOR,
          CommandId.ENCODING_AUTO);

  /**
   * A highlighter set as the Highlighters menus list it.
   *
   * @param set the set
   * @param active true when the set colors the views
   */
  public record SetChoice(HighlightSet set, boolean active) {}

  /**
   * An open document as View > Opened Files lists it.
   *
   * @param document its controller
   * @param text its path, or its tab title when it has no file
   * @param shown true for the shown document
   */
  public record OpenedFile(DocumentController document, String text, boolean shown) {}

  /**
   * The enabled items of a tab's context menu; Close This, Close All and Move to New Window are
   * always enabled.
   *
   * @param closeOthers another tab is open
   * @param closeLeft a tab is open to the left
   * @param closeRight a tab is open to the right
   * @param file the tab has a file: the path and tab name items
   */
  public record TabItems(
      boolean closeOthers, boolean closeLeft, boolean closeRight, boolean file) {}

  private final ApplicationController application;

  /**
   * Reads state from a controller.
   *
   * @param application the window's controller
   */
  public CommandStates(ApplicationController application) {
    this.application = application;
  }

  /**
   * Reports whether a command's item is checked.
   *
   * @param command any command
   * @return false for a command that is not {@link CommandId#check()}
   */
  public boolean checked(CommandId command) {
    if (!command.check()) {
      return false;
    }
    if (APPLICATION_CHECKS.contains(command)) {
      return applicationChecked(command);
    }
    DocumentController document = application.activeDocument();
    if (document == null) {
      return false;
    }
    if (command == CommandId.ENCODING_AUTO) {
      return document.state().hasFile() && EncodingChoices.forced(document).isEmpty();
    }
    return documentChecked(command, document);
  }

  /**
   * Reports whether a command's item is enabled. A disabled menu item's accelerator does not fire.
   *
   * @param command any command
   * @return true unless the command needs a document, a file or a favorite state it lacks
   */
  public boolean enabled(CommandId command) {
    DocumentController document = application.activeDocument();
    boolean file = document != null && document.state().hasFile();
    if (FILE_COMMANDS.contains(command)) {
      return file;
    }
    return switch (command) {
      case MOVE_TO_NEW_WINDOW -> document != null;
      case ADD_FAVORITE -> file && !application.isFavorite(document);
      case REMOVE_FAVORITE -> application.isFavorite(document);
      // Timestamps.
      case GO_TO_TIME, TIME_OFFSET, TIME_ZONE ->
          document != null && document.time().format() != null;
      default -> !command.check() || APPLICATION_CHECKS.contains(command) || document != null;
    };
  }

  /**
   * Returns the charset name the Encoding menu checks.
   *
   * @return the shown file's forced charset, or empty for Auto
   */
  public String forcedEncoding() {
    return EncodingChoices.forced(application.activeDocument());
  }

  /**
   * Lists the highlighter sets in their order.
   *
   * @return the sets; None is checked while none is active ({@link CommandId#NO_HIGHLIGHTERS})
   */
  public List<SetChoice> highlightSets() {
    HighlightSettings settings = application.highlightSettings();
    List<SetChoice> choices = new ArrayList<SetChoice>();
    for (HighlightSet set : settings.sets()) {
      choices.add(new SetChoice(set, settings.isActive(set.id())));
    }
    return choices;
  }

  // Timestamps.

  /**
   * A format as View > Timestamps > Format lists it.
   *
   * @param format the format, or null for none
   * @param label the menu text
   * @param chosen true for the shown document's format
   */
  public record FormatChoice(TimestampFormat format, String label, boolean chosen) {}

  /**
   * Lists the formats: the built-ins, the user formats, then None.
   *
   * @return the choices; none is chosen without a shown document
   */
  public List<FormatChoice> timeFormats() {
    DocumentController document = application.activeDocument();
    TimestampFormat current = document == null ? null : document.time().format();
    List<FormatChoice> choices = new ArrayList<FormatChoice>();
    for (TimestampFormat format : application.timeFormats()) {
      choices.add(new FormatChoice(format, TimeText.formatName(format), format.equals(current)));
    }
    choices.add(
        new FormatChoice(
            null, UiText.TIME_FORMAT_NONE.text(), document != null && current == null));
    return choices;
  }

  /**
   * Lists the open documents in tab order.
   *
   * @return one entry per tab
   */
  public List<OpenedFile> openedFiles() {
    DocumentController shown = application.activeDocument();
    List<OpenedFile> files = new ArrayList<OpenedFile>();
    for (DocumentController document : application.documents()) {
      String path = document.state().path();
      files.add(
          new OpenedFile(
              document, path.isEmpty() ? application.tabTitle(document) : path, document == shown));
    }
    return files;
  }

  /**
   * Returns the enabled items of a tab's context menu.
   *
   * @param document the tab's controller
   * @return the state
   */
  public TabItems tabItems(DocumentController document) {
    int index = application.documents().indexOf(document);
    int count = application.documents().size();
    return new TabItems(count > 1, index > 0, index < count - 1, document.state().hasFile());
  }

  private boolean applicationChecked(CommandId command) {
    ThemeMode theme = application.theme().mode();
    HighlightSettings highlights = application.highlightSettings();
    return switch (command) {
      case RUN_SEARCH_ON_ADD -> application.runSearchOnAdd();
      case FOLLOW_ON_LOAD -> application.preferences().follow().onLoad();
      case FOLLOW_ON_OVERSCROLL -> application.preferences().follow().onOverscroll();
      case TOGGLE_OVERVIEW -> application.preferences().overview();
      case TOGGLE_MATCH_HIGHLIGHT -> highlights.match().enabled();
      case THEME_LIGHT -> theme == ThemeMode.LIGHT;
      case THEME_DARK -> theme == ThemeMode.DARK;
      case THEME_SYSTEM -> theme == ThemeMode.SYSTEM;
      case NO_HIGHLIGHTERS -> highlights.active().isEmpty();
      case WRAP_ON_OPEN -> application.preferences().view().wrapOnOpen();
      case TOGGLE_WHITESPACE -> application.preferences().view().showWhitespace();
      case HIDE_ANSI -> application.preferences().view().hideAnsi();
      case BLOCK_SELECTION -> application.blockMode();
      case DEBUG_LOG_FILE -> application.debugLogFile();
      default -> false;
    };
  }

  private static boolean documentChecked(CommandId command, DocumentController document) {
    SearchOptions options = document.search().options();
    Visibility visibility = document.filtered().visibility();
    return switch (command) {
      case FOLLOW -> document.following();
      case TOGGLE_LINE_NUMBERS -> document.lineNumbers();
      case TOGGLE_FILTERED_LINE_NUMBERS -> document.filtered().lineNumbers();
      case SHOW_MARKS_AND_MATCHES -> visibility == Visibility.MARKS_AND_MATCHES;
      case SHOW_MARKS -> visibility == Visibility.MARKS;
      case SHOW_MATCHES -> visibility == Visibility.MATCHES;
      case TOGGLE_CASE -> options.caseSensitive();
      case TOGGLE_REGEX -> options.mode() == MatchMode.REGEX;
      case TOGGLE_INVERSE -> options.inverse();
      case TOGGLE_COMBINING -> options.combining();
      case TOGGLE_AUTO_REFRESH -> options.autoRefresh();
      case TOGGLE_KEEP_RESULTS -> options.keepResults();
      case TOGGLE_RANGE -> options.range();
      case TOGGLE_WRAP -> document.wrap();
      default -> false;
    };
  }
}
