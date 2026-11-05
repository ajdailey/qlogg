package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.PositionStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.TimeSettings;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.MainView;
import io.github.ajdailey.qlogg.app.view.TimeStatus;
import io.github.ajdailey.qlogg.app.view.TimeText;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.text.LogText;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import io.github.ajdailey.qlogg.core.time.SpreadMode;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import io.github.ajdailey.qlogg.core.time.TimeReader;
import io.github.ajdailey.qlogg.core.time.TimestampFormat;
import io.github.ajdailey.qlogg.core.time.TimestampParser;
import io.github.ajdailey.qlogg.core.time.UserFormat;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The window's timestamp commands and each new document's time state. Confined to the UI thread.
 *
 * <p>A new document takes the format the user chose for its file ({@code PositionStore} {@code
 * .timeFormat}, where {@link TimeSettings#NO_FORMAT} or an unknown id is none), else the format
 * detected at open, and the zone chosen for the file ({@code .zone}), else {@code time.zone}. A
 * choice from the menu or a dialog applies at once and is recorded for the file; pasted text
 * records nothing. The Timestamp Formats editor's preview reads the caret line's first {@link
 * TimeReader#PREFIX_CHARS} chars on a {@code qlogg-read-} worker before the editor opens.
 */
final class TimeFeatures {
  private static final Logger LOG = LoggerFactory.getLogger(TimeFeatures.class);

  private final ApplicationController application;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final ConfigStore config;

  TimeFeatures(
      ApplicationController application,
      Workers workers,
      UiDispatcher ui,
      MainView view,
      ConfigStore config) {
    this.application = application;
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.config = config;
  }

  /** Sets a new document's format and zones. */
  void configure(DocumentController document) {
    TimeSettings settings = application.preferences().time();
    TimestampFormat format = document.document().timeFormat();
    ZoneId zone = settings.zoneOrSystem();
    if (!document.document().inMemory()) {
      PositionStore.Entry entry = config.positions().get(document.document().path());
      if (!entry.timeFormat().isEmpty()) {
        format = settings.format(entry.timeFormat());
      }
      if (!entry.zone().isEmpty()) {
        try {
          zone = ZoneId.of(entry.zone());
        } catch (DateTimeException e) {
          LOG.debug(
              "Ignoring the remembered zone of {}", LogText.oneLine(document.document().path()));
        }
      }
    }
    document.time().configure(format, zone, settings.displayZoneOrSystem());
    // Time spread.
    document.overview().setSpreadMode(application.preferences().view().timeSpread());
  }

  /**
   * Runs a timestamp command.
   *
   * @return false when the command is not one of them
   */
  boolean execute(CommandId command) {
    DocumentController document = application.activeDocument();
    switch (command) {
      case GO_TO_TIME -> askGoToTime(document);
      case TIME_OFFSET -> askOffset(document);
      case TIME_ZONE -> askZone(document);
      case TIMESTAMP_FORMATS -> editFormats(document);
      // Time sync.
      case SCROLL_TABS_TO_TIME -> scrollTabs(document);
      // Time spread.
      case TIME_SPREAD_OFF -> setSpread(SpreadMode.OFF);
      case TIME_SPREAD_ACTIVITY -> setSpread(SpreadMode.ACTIVITY);
      case TIME_SPREAD_PAUSES -> setSpread(SpreadMode.PAUSES);
      default -> {
        return false;
      }
    }
    return true;
  }

  /**
   * Lists the formats the Format cascade offers: the built-ins, then the user formats.
   *
   * @return the formats
   */
  List<TimestampFormat> formats() {
    List<TimestampFormat> formats = new ArrayList<TimestampFormat>();
    for (BuiltInFormat format : BuiltInFormat.values()) {
      formats.add(format);
    }
    formats.addAll(application.preferences().time().formats());
    return formats;
  }

  /**
   * Applies a format the user chose and records it for the file.
   *
   * @param document the document
   * @param format the format, or null for none
   */
  void setFormat(DocumentController document, TimestampFormat format) {
    TimeController time = document.time();
    time.configure(format, time.zone(), time.displayZone());
    if (!document.document().inMemory()) {
      config
          .positions()
          .setTimeFormat(
              document.document().path(), format == null ? TimeSettings.NO_FORMAT : format.id());
      document.host().documentStateChanged();
    }
  }

  /**
   * Applies a zone the user chose and records it for the file.
   *
   * @param document the document
   * @param zoneId a zone id, or empty for {@code time.zone}
   */
  void setZone(DocumentController document, String zoneId) {
    TimeSettings settings = application.preferences().time();
    ZoneId zone = settings.zoneOrSystem();
    if (!zoneId.isEmpty()) {
      try {
        zone = ZoneId.of(zoneId);
      } catch (DateTimeException e) {
        return;
      }
    }
    TimeController time = document.time();
    time.configure(time.format(), zone, time.displayZone());
    if (!document.document().inMemory()) {
      config.positions().setZone(document.document().path(), zoneId);
      document.host().documentStateChanged();
    }
  }

  /**
   * Replaces the user formats and saves them. An open document's user format takes the edited
   * format of the same name, or none when it was removed.
   *
   * @param formats the formats in order
   */
  void setUserFormats(List<UserFormat> formats) {
    TimeSettings settings = application.preferences().time().withFormats(formats);
    application.setPreferences(application.preferences().withTime(settings));
    for (ApplicationController window : application.windowSet().windows()) {
      for (DocumentController open : window.documents()) {
        TimeController time = open.time();
        if (time.format() instanceof UserFormat user) {
          time.configure(settings.format(user.id()), time.zone(), time.displayZone());
        }
      }
    }
  }

  /** Saves the time-spread mode and applies it to every open document of every window. */
  private void setSpread(SpreadMode mode) {
    Preferences current = application.preferences();
    application.setPreferences(current.withView(current.view().withTimeSpread(mode)));
    for (ApplicationController window : application.windowSet().windows()) {
      for (DocumentController open : window.documents()) {
        open.overview().setSpreadMode(mode);
      }
    }
  }

  private void askGoToTime(final DocumentController document) {
    if (document == null || document.time().format() == null) {
      return;
    }
    TimeController time = document.time();
    TimeStatus status = document.timeStatus();
    String initial = TimeText.caret(status, time.offset(), time.displayZone());
    LocalDate date = null;
    if (!initial.isEmpty()) {
      date = TimeText.date(status.caret() + time.offset(), time.displayZone());
    }
    view.askGoToTime(
        initial,
        time.displayZone(),
        date,
        new MainView.TimeListener() {
          @Override
          public void timeChosen(long nanos) {
            if (!document.isClosed()) {
              document.time().goToTime(nanos);
            }
          }
        });
  }

  private void scrollTabs(DocumentController document) {
    if (document == null) {
      return;
    }
    if (document.time().format() == null) {
      view.showNotice(UiText.NOTICE_TIME_NO_FORMAT.text());
      return;
    }
    application.windowSet().timeSync().scrollAll(document);
  }

  private void askOffset(final DocumentController document) {
    if (document == null || document.time().format() == null) {
      return;
    }
    TimeController time = document.time();
    TimeStatus status = document.timeStatus();
    view.askTimeOffset(
        time.offset(),
        status.caret() == TimestampParser.NONE ? TimeText.INVALID : status.caret(),
        status.caretDigits(),
        time.displayZone(),
        new MainView.TimeListener() {
          @Override
          public void timeChosen(long nanos) {
            if (!document.isClosed()) {
              document.time().setOffset(nanos);
            }
          }
        });
  }

  private void askZone(final DocumentController document) {
    if (document == null || document.time().format() == null) {
      return;
    }
    String current = "";
    if (!document.document().inMemory()) {
      current = config.positions().get(document.document().path()).zone();
    }
    view.askTimeZone(
        current,
        application.preferences().time().zoneOrSystem(),
        new MainView.NameListener() {
          @Override
          public void nameChosen(String zoneId) {
            if (!document.isClosed()) {
              setZone(document, zoneId);
            }
          }
        });
  }

  /** Reads the caret line's start on a worker when a document shows, then opens the editor. */
  private void editFormats(final DocumentController document) {
    if (document == null) {
      showFormats(null, "");
      return;
    }
    final LogDocument file = document.document();
    final LineIndex index = file.index();
    final long line = document.sourceLine(document.selection().caret().line());
    if (line < 0 || line >= index.lineCount()) {
      showFormats(document, "");
      return;
    }
    boolean started =
        workers.tryStart(
            Workers.Kind.READ,
            new Runnable() {
              @Override
              public void run() {
                String text = "";
                boolean admitted = file.enter();
                try {
                  if (admitted) {
                    CharSequence decoded = file.newReader().decodedLine(index, line);
                    int end = Math.min(decoded.length(), TimeReader.PREFIX_CHARS);
                    text = decoded.subSequence(0, end).toString();
                  }
                } catch (IOException | UncheckedIOException e) {
                  LOG.debug("Cannot read the preview line: {}", LogText.oneLine(e.getMessage()));
                } finally {
                  if (admitted) {
                    file.exit();
                  }
                  final String sample = text;
                  ui.asyncExec(
                      new Runnable() {
                        @Override
                        public void run() {
                          if (!application.isShuttingDown()) {
                            showFormats(document, sample);
                          }
                        }
                      });
                }
              }
            });
    if (!started) {
      showFormats(document, "");
    }
  }

  private void showFormats(DocumentController document, String sample) {
    TimeSettings settings = application.preferences().time();
    TimeController time = document == null || document.isClosed() ? null : document.time();
    view.showTimestampFormats(
        settings.formats(),
        time == null ? "" : sample,
        time == null ? TimeContext.current(settings.zoneOrSystem()) : time.context(),
        time == null ? settings.displayZoneOrSystem() : time.displayZone(),
        new MainView.FormatsListener() {
          @Override
          public void formatsChosen(List<UserFormat> formats) {
            setUserFormats(formats);
          }
        });
  }
}
