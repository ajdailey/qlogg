package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.UiState;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.MainView;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File > Recently Closed, Open All on it and on Recent Files, and Clear Recent Files. Closing a tab
 * lists its file first in Recently Closed, at most {@link UiState#MAX_CLOSED}; opening the file in
 * a tab drops it. The list is {@code closed.N} in {@code qlogg.conf}. A reopened file opens at its
 * remembered position through the normal open path. Reopen Closed Tab takes the newest entry off
 * the list and opens it. Confined to the UI thread.
 *
 * <p>Open All checks each listed file on a {@code qlogg-lifecycle-} worker, then opens the regular,
 * readable ones in list order; a file open already shows its tab. One notice names the files
 * skipped.
 */
final class RecentFiles {
  private static final Logger LOG = LoggerFactory.getLogger(RecentFiles.class);

  private final RecentFilesHost host;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;

  RecentFiles(RecentFilesHost host, Workers workers, UiDispatcher ui, MainView view) {
    this.host = host;
    this.workers = workers;
    this.ui = ui;
    this.view = view;
  }

  /**
   * Lists a closed tab's file first; clipboard text is not listed.
   *
   * @param document the closed tab's document
   */
  void closed(LogDocument document) {
    if (!document.inMemory()) {
      setState(host.preferences().state().addClosed(document.path().toString()));
    }
  }

  // Reopen Closed Tab.

  /** Drops the newest Recently Closed file from the list and opens it, if the list has one. */
  void reopenClosed() {
    List<String> closed = host.preferences().state().closedFiles();
    if (closed.isEmpty()) {
      return;
    }
    String path = closed.get(0);
    setState(host.preferences().state().removeClosed(path));
    host.openListed(path);
  }

  /** Empties Recently Closed. */
  void clearClosed() {
    setState(host.preferences().state().withClosedFiles(List.of()));
  }

  // Clear Recent Files.

  /** Empties Recent Files. */
  void clearRecent() {
    setState(host.preferences().state().withRecentFiles(List.of()));
  }

  private void setState(UiState state) {
    Preferences current = host.preferences();
    if (!state.equals(current.state())) {
      host.setPreferences(current.withState(state));
    }
  }

  /**
   * Opens every listed file that is a regular, readable file, in list order.
   *
   * @param listed the paths as the list holds them
   */
  void openAll(List<String> listed) {
    final List<String> paths = List.copyOf(listed);
    if (paths.isEmpty()) {
      return;
    }
    boolean started =
        workers.tryStart(
            Workers.Kind.LIFECYCLE,
            new Runnable() {
              @Override
              public void run() {
                check(paths);
              }
            });
    if (!started) {
      LOG.debug("Open All not run: workers are closed");
    }
  }

  @WorkerThread
  private void check(List<String> paths) {
    final List<FileRequest> requests = new ArrayList<FileRequest>();
    final List<String> skipped = new ArrayList<String>();
    for (String text : paths) {
      Path path;
      try {
        path = Path.of(text);
      } catch (InvalidPathException e) {
        skipped.add(text);
        continue;
      }
      if (Files.isRegularFile(path) && Files.isReadable(path)) {
        requests.add(new FileRequest(path, 0));
      } else {
        Path name = path.getFileName();
        skipped.add(name == null ? text : name.toString());
      }
    }
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (host.isShuttingDown()) {
              return;
            }
            host.open(requests);
            if (!skipped.isEmpty()) {
              view.showNotice(
                  UiText.NOTICE_OPEN_ALL_SKIPPED.format(
                      skipped.size(), String.join(", ", skipped)));
            }
          }
        });
  }
}
