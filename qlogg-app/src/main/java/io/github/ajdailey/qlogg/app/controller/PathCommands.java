package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.MainView;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A tab's path commands: Copy Path, Copy File Name, Open Containing Folder and Open in Editor. On a
 * document without a file each shows the no-file notice. The folder and the file open through
 * {@link HelpController#launch}. Confined to the UI thread.
 */
public final class PathCommands {
  /** File name extensions Windows runs rather than opens; lower case, with the dot. */
  static final List<String> WINDOWS_EXECUTABLE =
      List.of(
          ".bat", ".cmd", ".com", ".cpl", ".exe", ".hta", ".js", ".jse", ".lnk", ".msc", ".msi",
          ".pif", ".ps1", ".reg", ".scr", ".url", ".vbe", ".vbs", ".wsf", ".wsh");

  private static final Logger LOG = LoggerFactory.getLogger(PathCommands.class);

  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final HelpController help;
  private final BooleanSupplier closing;

  /**
   * Creates the commands of one window.
   *
   * @param closing true once the window stops accepting operations; drops a pending launch
   */
  PathCommands(
      Workers workers,
      UiDispatcher ui,
      MainView view,
      HelpController help,
      BooleanSupplier closing) {
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.help = help;
    this.closing = closing;
  }

  /**
   * Runs a path command on a tab.
   *
   * @param command {@code COPY_PATH}, {@code COPY_FILE_NAME}, {@code OPEN_FOLDER}, else Open in
   *     Editor
   * @param document an open document's controller
   */
  void run(CommandId command, DocumentController document) {
    switch (command) {
      case COPY_PATH -> copyPath(document);
      case COPY_FILE_NAME -> copyFileName(document);
      case OPEN_FOLDER -> openContainingFolder(document);
      default -> openInEditor(document);
    }
  }

  /**
   * Puts a tab's full path on the clipboard.
   *
   * @param document an open document's controller
   */
  public void copyPath(DocumentController document) {
    Path path = document.document().path();
    if (path == null) {
      view.showNotice(UiText.NOTICE_NO_FILE.text());
      return;
    }
    view.setText(path.toString());
  }

  /**
   * Puts a tab's file name on the clipboard.
   *
   * @param document an open document's controller
   */
  public void copyFileName(DocumentController document) {
    Path path = document.document().path();
    Path name = path == null ? null : path.getFileName();
    if (name == null) {
      view.showNotice(UiText.NOTICE_NO_FILE.text());
      return;
    }
    view.setText(name.toString());
  }

  /**
   * Opens the folder holding a tab's file in the system file manager.
   *
   * @param document an open document's controller
   */
  public void openContainingFolder(DocumentController document) {
    Path path = document.document().path();
    Path folder = path == null ? null : path.getParent();
    if (folder == null) {
      view.showNotice(UiText.NOTICE_NO_FILE.text());
      return;
    }
    help.launch(folder);
  }

  /**
   * Opens a tab's file in the application the system associates with it. A file the system would
   * run instead is refused with a notice: an executable file, or where {@link
   * MainView#launchesByExtension} a {@link #WINDOWS_EXECUTABLE} extension. The check reads the
   * file's attributes on a {@code qlogg-lifecycle-} worker.
   */
  void openInEditor(DocumentController document) {
    final Path path = document.document().path();
    if (path == null) {
      view.showNotice(UiText.NOTICE_NO_FILE.text());
      return;
    }
    final boolean windows = view.launchesByExtension();
    boolean started =
        workers.tryStart(
            Workers.Kind.LIFECYCLE,
            new Runnable() {
              @Override
              public void run() {
                final boolean runnable = runsInsteadOfOpening(path, windows);
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        if (closing.getAsBoolean()) {
                          return;
                        }
                        if (runnable) {
                          view.showNotice(
                              UiText.NOTICE_EDITOR_REFUSED.format(
                                  String.valueOf(path.getFileName())));
                        } else {
                          help.launch(path);
                        }
                      }
                    });
              }
            });
    if (!started) {
      LOG.debug("Editor not opened: workers are closed");
    }
  }

  /**
   * Reports whether the system would run a file rather than open it.
   *
   * @param path the file
   * @param windows true on Windows, which decides by extension
   * @return true for an executable file or extension
   */
  @WorkerThread
  static boolean runsInsteadOfOpening(Path path, boolean windows) {
    if (windows) {
      Path name = path.getFileName();
      String text = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
      for (String extension : WINDOWS_EXECUTABLE) {
        if (text.endsWith(extension)) {
          return true;
        }
      }
      return false;
    }
    return Files.isExecutable(path);
  }
}
