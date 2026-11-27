package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.PositionStore;
import io.github.ajdailey.qlogg.core.columns.ColumnDetector;
import io.github.ajdailey.qlogg.core.columns.ColumnFormat;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;

/**
 * The window's column commands and each new tab's column state. Confined to the UI thread.
 *
 * <p>A new tab takes the format the user chose for its file ({@code PositionStore} {@code
 * .columns}), else the format detected at open. A chosen format opens in column view, {@link
 * PositionStore#NO_COLUMNS} in text view with the detected format for Toggle Columns, and a
 * detected one in column view when {@code view.columns.auto} is on. {@code .header} overrides the
 * CSV and TSV header guess. A tab whose format is {@code clef} or {@code log4j-xml}, with no
 * timestamp format chosen for the file, takes the timestamp format of the same name. Toggle Columns
 * and {@link #setHeader} apply at once and are recorded for the file; pasted text records nothing.
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
    PositionStore.Entry entry =
        file.inMemory() ? PositionStore.Entry.NONE : config.positions().get(file.path());
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
   * Runs a column command.
   *
   * @return false when the command is not one of them
   */
  boolean execute(CommandId command) {
    if (command != CommandId.TOGGLE_COLUMNS) {
      return false;
    }
    DocumentController document = host.activeDocument();
    if (document != null) {
      toggle(document);
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
   * Makes a CSV or TSV tab's first line its header, or a record, and records the choice for the
   * file.
   *
   * @param document the tab
   * @param header true for a header line
   */
  void setHeader(DocumentController document, boolean header) {
    ColumnController columns = document.columns();
    ColumnSchema current = columns.schema();
    if (current == null || current.delimiter() == 0) {
      return;
    }
    columns.configure(schema(document, current.format(), header), columns.shown());
    record(document, null, header);
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
