package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.PositionStore;
import io.github.ajdailey.qlogg.core.columns.ColumnDetector;
import io.github.ajdailey.qlogg.core.columns.ColumnFormat;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * The window's column commands and each new tab's column state. Confined to the UI thread.
 *
 * <p>A new tab takes the format the user chose for its file ({@code PositionStore} {@code
 * .columns}), else the format detected at open. A chosen format opens in column view, {@link
 * PositionStore#NO_COLUMNS} in text view with the detected format for Toggle Columns, and a
 * detected one in column view when {@code view.columns.auto} is on. {@code .header} overrides the
 * CSV and TSV header guess. A tab whose format is {@code clef} or {@code log4j-xml}, with no
 * timestamp format chosen for the file, takes the timestamp format of the same name. Toggle
 * Columns, the View > Columns format items ({@link FormatCommands}) and {@link #setHeader} apply at
 * once and are recorded for the file; pasted text records nothing.
 */
final class ColumnFeatures {
  private final TimeFeaturesHost host;
  private final ConfigStore config;

  ColumnFeatures(TimeFeaturesHost host, ConfigStore config) {
    this.host = host;
    this.config = config;
  }

  /** Sets a new tab's format, view and, for CLEF and log4j XML, timestamp format. */
  void configure(DocumentController document) {
    LogDocument file = document.document();
    PositionStore.Entry entry = entry(document);
    ColumnSchema detected = file.columns();
    ColumnFormat format = detected == null ? null : detected.format();
    boolean shown = format != null && host.preferences().view().columnsAuto();
    if (entry.columns().equals(PositionStore.NO_COLUMNS)) {
      shown = false;
    } else if (ColumnFormat.byId(entry.columns()) != null) {
      format = ColumnFormat.byId(entry.columns());
      shown = true;
    }
    Boolean header = entry.header().isEmpty() ? null : entry.header().equals("1");
    ColumnSchema schema =
        detected != null && format == detected.format() && header == null
            ? detected
            : schema(document, format, header);
    if (schema != null && entry.timeFormat().isEmpty()) {
      BuiltInFormat stamps = stampFormat(schema.format());
      TimeController time = document.time();
      if (stamps != null && time.format() != stamps) {
        time.configure(stamps, time.zone(), time.displayZone());
      }
    }
    document.columns().configure(schema, shown);
  }

  /** Returns the timestamp format named after a column format, or null. */
  private static BuiltInFormat stampFormat(ColumnFormat format) {
    return switch (format) {
      case CLEF -> BuiltInFormat.CLEF;
      case LOG4J_XML -> BuiltInFormat.LOG4J_XML;
      default -> null;
    };
  }

  /** Builds a format's schema from the file's head; null without a format or a time format. */
  private static ColumnSchema schema(
      DocumentController document, ColumnFormat format, Boolean header) {
    if (format == null) {
      return null;
    }
    LogDocument file = document.document();
    TimeController time = document.time();
    return ColumnDetector.schema(
        format, file.head(), file.encoding(), header, time.format(), time.context());
  }

  /**
   * Runs a column command: Toggle Columns, a format item or First Line Is Header.
   *
   * @return false when the command is not one of them
   */
  boolean execute(CommandId command) {
    if (command != CommandId.TOGGLE_COLUMNS && !FormatCommands.handles(command)) {
      return false;
    }
    DocumentController document = host.activeDocument();
    if (document == null) {
      return true;
    }
    if (command == CommandId.TOGGLE_COLUMNS) {
      toggle(document);
    } else if (command == CommandId.COLUMNS_TEXT) {
      document.columns().setShown(false);
      record(document, PositionStore.NO_COLUMNS, null);
    } else if (command == CommandId.FIRST_LINE_HEADER) {
      ColumnSchema schema = document.columns().schema();
      setHeader(document, schema == null || !schema.header());
    } else {
      choose(document, FormatCommands.format(command));
    }
    return true;
  }

  /**
   * Switches a tab between text view and its format and records the choice for the file.
   *
   * @param document the tab
   */
  void toggle(DocumentController document) {
    ColumnController columns = document.columns();
    if (columns.schema() == null) {
      return;
    }
    columns.setShown(!columns.shown());
    record(
        document,
        columns.shown() ? columns.schema().format().id() : PositionStore.NO_COLUMNS,
        null);
  }

  /**
   * Shows a tab in a format and records the choice for the file. Every tab of the file takes the
   * format, so a filter tab reads its parent's records; the others keep their view. A CLEF or log4j
   * XML choice also sets the timestamp format of the same name when none is chosen for the file.
   *
   * @param document the tab
   * @param format the format; {@link ColumnFormat#TIME} needs the tab's timestamp format
   */
  void choose(DocumentController document, ColumnFormat format) {
    ColumnSchema current = document.columns().schema();
    if (current != null && current.format() == format) {
      document.columns().setShown(true);
    } else {
      BuiltInFormat stamps = stampFormat(format);
      if (stamps != null && entry(document).timeFormat().isEmpty()) {
        for (DocumentController tab : tabsOf(document)) {
          TimeController time = tab.time();
          time.configure(stamps, time.zone(), time.displayZone());
        }
      }
      String header = entry(document).header();
      ColumnSchema next = schema(document, format, header.isEmpty() ? null : header.equals("1"));
      if (next == null) {
        return;
      }
      apply(document, next);
    }
    record(document, format.id(), null);
  }

  /**
   * Makes a CSV or TSV file's first line its header, or a record, in every tab of the file, and
   * records the choice for the file.
   *
   * @param document a tab of the file
   * @param header true for a header line
   */
  void setHeader(DocumentController document, boolean header) {
    ColumnSchema current = document.columns().schema();
    if (current == null || current.delimiter() == 0) {
      return;
    }
    apply(document, schema(document, current.format(), header));
    record(document, null, header);
  }

  /** Sets a schema on every tab of a document's file, shows it in {@code document}, then scans. */
  private void apply(DocumentController document, ColumnSchema next) {
    List<DocumentController> tabs = tabsOf(document);
    for (DocumentController tab : tabs) {
      tab.columns().configure(next, tab == document || tab.columns().shown());
    }
    for (DocumentController tab : tabs) {
      tab.columns().scanChosen();
    }
  }

  /** Returns the open tabs showing a tab's document: its file tab and the filter tabs over it. */
  private List<DocumentController> tabsOf(DocumentController document) {
    List<DocumentController> tabs = new ArrayList<DocumentController>();
    for (DocumentController tab : host.allDocuments()) {
      if (tab.document() == document.document()) {
        tabs.add(tab);
      }
    }
    if (!tabs.contains(document)) {
      tabs.add(document);
    }
    return tabs;
  }

  private PositionStore.Entry entry(DocumentController document) {
    LogDocument file = document.document();
    return file.inMemory() ? PositionStore.Entry.NONE : config.positions().get(file.path());
  }

  private void record(DocumentController document, String formatId, Boolean header) {
    LogDocument file = document.document();
    if (file.inMemory()) {
      return;
    }
    if (formatId != null) {
      config.positions().setColumns(file.path(), formatId);
    }
    if (header != null) {
      config.positions().setHeader(file.path(), header);
    }
    document.host().documentStateChanged();
  }
}
