package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.core.columns.ColumnFormat;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;

/**
 * The format radio items of View > Columns and First Line Is Header: the format each item chooses,
 * and the item's enabled and checked state for the shown tab.
 *
 * <p>Text is checked in text view, a format in column view on it. Timestamp needs the tab's
 * timestamp format. First Line Is Header is enabled while the tab's format is CSV or TSV, shown or
 * reached by Toggle Columns, and checked while the first line is the header.
 */
final class FormatCommands {
  private FormatCommands() {}

  /**
   * Reports whether a command is a format item or First Line Is Header.
   *
   * @param command a command
   * @return true for {@link CommandId#COLUMNS_TEXT} through {@link CommandId#FIRST_LINE_HEADER}
   */
  static boolean handles(CommandId command) {
    return command == CommandId.COLUMNS_TEXT
        || command == CommandId.FIRST_LINE_HEADER
        || format(command) != null;
  }

  /**
   * Returns the format a radio item chooses.
   *
   * @param command a command
   * @return the format, or null for Text and any other command
   */
  static ColumnFormat format(CommandId command) {
    return switch (command) {
      case COLUMNS_CSV -> ColumnFormat.CSV;
      case COLUMNS_TSV -> ColumnFormat.TSV;
      case COLUMNS_CLF -> ColumnFormat.CLF;
      case COLUMNS_CLEF -> ColumnFormat.CLEF;
      case COLUMNS_LOG4J_XML -> ColumnFormat.LOG4J_XML;
      case COLUMNS_TIME -> ColumnFormat.TIME;
      default -> null;
    };
  }

  /**
   * Reports whether an item of {@link #handles} is enabled.
   *
   * @param command the item's command
   * @param document the shown tab, or null
   * @return false without a tab
   */
  static boolean enabled(CommandId command, DocumentController document) {
    if (document == null) {
      return false;
    }
    return switch (command) {
      case COLUMNS_TIME -> document.time().format() != null;
      case FIRST_LINE_HEADER -> delimited(document.columns().schema());
      default -> true;
    };
  }

  /**
   * Reports whether an item of {@link #handles} is checked.
   *
   * @param command the item's command
   * @param document the shown tab
   * @return the state
   */
  static boolean checked(CommandId command, DocumentController document) {
    ColumnController columns = document.columns();
    ColumnSchema schema = columns.schema();
    if (command == CommandId.COLUMNS_TEXT) {
      return !columns.shown();
    }
    if (command == CommandId.FIRST_LINE_HEADER) {
      return delimited(schema) && schema.header();
    }
    return columns.shown() && schema.format() == format(command);
  }

  private static boolean delimited(ColumnSchema schema) {
    return schema != null && schema.delimiter() != 0;
  }
}
