package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.core.watch.FileChange;

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

  /** The tab detached; stops the role's background work. */
  void detached();

  /**
   * Closes the document after the tab detached.
   *
   * @param owner the tab
   */
  void close(DocumentController owner);
}
