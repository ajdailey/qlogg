package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.text.LogText;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A file tab's document: the tab indexes it, reopens it on a change and closes it. */
final class OwnedDocument implements DocumentRole {
  private static final Logger LOG = LoggerFactory.getLogger(OwnedDocument.class);

  @Override
  public void start(DocumentController owner) {
    owner.startIndexing();
  }

  @Override
  public void changed(DocumentController owner, FileChange change) {
    owner.changedOnDisk(change);
  }

  @Override
  public DocumentController parent() {
    return null;
  }

  @Override
  public void flushed() {
    // Growth reaches this tab's search through the flush itself.
  }

  @Override
  public boolean owns() {
    return true;
  }

  @Override
  public void detached() {
    // Indexing and the watch end when the document closes.
  }

  /** Closes the document on a {@code qlogg-lifecycle-} worker. */
  @Override
  public void close(DocumentController owner) {
    if (!owner.workers().tryStart(Workers.Kind.LIFECYCLE, owner.document()::close)) {
      LOG.warn("Cannot close {}: workers are closed", LogText.oneLine(owner.document().path()));
    }
  }
}
