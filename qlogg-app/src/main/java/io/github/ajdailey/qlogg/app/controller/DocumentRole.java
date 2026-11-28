package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.core.watch.FileChange;
import java.time.ZoneId;

/**
 * What a tab does with its document: {@link OwnedDocument} for a file tab, which indexes, reopens
 * and closes it, and {@link Derivation} for a filter tab, which shares its root tab's document and
 * grows its rows. {@link DocumentController} picks one at construction. Confined to the UI thread.
 */
interface DocumentRole {
  /**
   * Starts the tab's work on the document, after the controller listens to it.
   *
   * @param owner the tab
   */
  void start(DocumentController owner);

  /**
   * Handles a change on disk other than growth.
   *
   * @param owner the tab
   * @param change the change
   */
  void changed(DocumentController owner, FileChange change);

  /**
   * Returns the tab whose rows this tab's rows are drawn from.
   *
   * @return a filter tab's parent, or null for a file tab
   */
  DocumentController parent();

  /** The tab applied a flush of the document's progress. */
  void flushed();

  /**
   * Reports whether the tab owns its document: closes it, remembers its position and saves it in
   * the session.
   *
   * @return true for a file tab
   */
  boolean owns();

  /**
   * Returns the tab's title.
   *
   * @return the file name or a rotated set's title; a filter tab's root title and search
   */
  String title();

  /**
   * Returns the tab tooltip's lines after the path.
   *
   * @param zone the zone file times show in
   * @return a rotated set's files, a filter tab's searches, or empty
   */
  String details(ZoneId zone);

  /**
   * Returns the status bar note.
   *
   * @return a rotated set's left-out files, a filter tab's row count, or empty
   */
  String note();

  /**
   * Counts the rows the main view lists, as the tab data dot measures growth.
   *
   * @param lineCount the document's line count
   * @return the line count for a file tab, the rows for a filter tab
   */
  long rowCount(long lineCount);

  /**
   * Reports whether the tab's rows lag its parent's, as while a restored or reloaded filter tab
   * derives. The tab data dot does not count rows added meanwhile as growth.
   *
   * @return true while a filter tab's rows lag its parent's at its level or one above; false for a
   *     file tab
   */
  boolean deriving();

  /** The tab detached; stops the role's background work. */
  void detached();

  /**
   * Closes the document after the tab detached.
   *
   * @param owner the tab
   */
  void close(DocumentController owner);
}
