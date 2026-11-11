package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.about.AboutInfo;
import io.github.ajdailey.qlogg.app.about.UserGuide;
import io.github.ajdailey.qlogg.app.config.Options;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.log.LogFiles;
import io.github.ajdailey.qlogg.app.view.MainView;
import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import io.github.ajdailey.qlogg.core.text.LogText;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One window's Help and support commands: About, the license notices, Show Log, Debug Log File, the
 * log and settings folders, Report an Issue and the user guide. Confined to the UI thread; each
 * file or jar read runs on a {@code qlogg-lifecycle-} worker, and its result is shown only while
 * the window is open. None touches the tabs.
 */
final class HelpController {
  private static final Logger LOG = LoggerFactory.getLogger(HelpController.class);

  private final WindowSet set;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final BooleanSupplier closing;

  /**
   * Creates the Help commands of a window.
   *
   * @param set the window's set: settings, log files and recent log lines
   * @param view the window
   * @param closing true once the window is closing; results arriving later are dropped
   */
  HelpController(WindowSet set, MainView view, BooleanSupplier closing) {
    this.set = set;
    this.workers = set.workers();
    this.ui = set.ui();
    this.view = view;
    this.closing = closing;
  }

  /** Collects the About facts on a worker, then shows the dialog. */
  void showAbout() {
    final String toolkit = view.toolkitDescription();
    final SettingsLocation location = set.config().location();
    start(
        "About not shown",
        new Runnable() {
          @Override
          public void run() {
            final AboutInfo info = AboutInfo.collect(toolkit, location);
            post(
                new Runnable() {
                  @Override
                  public void run() {
                    view.showAbout(info);
                  }
                });
          }
        });
  }

  /** Reads the license notices from the jar on a worker, then shows them. */
  void showLicenses() {
    start(
        "Licenses not shown",
        new Runnable() {
          @Override
          public void run() {
            String text;
            try {
              text = AboutInfo.licenseText();
            } catch (IOException e) {
              text = UiText.LICENSES_READ_FAILED.format(LogText.oneLine(e.getMessage()));
            }
            final String shown = text;
            post(
                new Runnable() {
                  @Override
                  public void run() {
                    view.showText(UiText.LICENSES_TITLE.text(), shown);
                  }
                });
          }
        });
  }

  /** Shows the recent log lines and clears the warning marker in every window. */
  void showLog() {
    List<String> lines = set.readRecent();
    view.showLog(
        lines.isEmpty()
            ? UiText.SHOW_LOG_EMPTY.text()
            : String.join(System.lineSeparator(), lines));
  }

  /**
   * Opens or closes the debug log file on a worker and saves the choice; the check mark follows the
   * file, so it stays clear when the file does not open.
   *
   * @param on true to open it
   */
  void setDebugLogFile(final boolean on) {
    Preferences current = set.preferences().get();
    Options options = current.options();
    if (options.debugLogFile() != on) {
      set.setPreferences(current.withOptions(options.withDebugLogFile(on)));
    }
    final LogFiles files = set.logFiles();
    start(
        "Debug log file not changed",
        new Runnable() {
          @Override
          public void run() {
            String failure = null;
            if (on) {
              try {
                files.attach();
              } catch (IOException e) {
                failure = LogText.oneLine(e.getMessage());
                LOG.warn("Cannot open the debug log file: {}", failure);
              }
            } else {
              files.detach();
            }
            final String notice =
                failure != null
                    ? UiText.NOTICE_LOG_FILE_FAILED.format(failure)
                    : on
                        ? UiText.NOTICE_LOG_FILE_ON.format(files.directory())
                        : UiText.NOTICE_LOG_FILE_OFF.text();
            post(
                new Runnable() {
                  @Override
                  public void run() {
                    view.showNotice(notice);
                  }
                });
          }
        });
  }

  /**
   * Creates a folder owner-only on a worker when missing, then opens it in the file manager.
   *
   * @param folder the log or settings folder
   */
  void openFolder(final Path folder) {
    start(
        "Folder not opened",
        new Runnable() {
          @Override
          public void run() {
            Path created = null;
            String failure = null;
            try {
              created = SafePaths.settingsDirectory(folder);
            } catch (IOException e) {
              failure = LogText.oneLine(e.getMessage());
              LOG.warn("Cannot create {}: {}", LogText.oneLine(folder), failure);
            }
            final Path target = created;
            final String notice =
                failure == null ? null : UiText.NOTICE_FOLDER_FAILED.format(folder, failure);
            post(
                new Runnable() {
                  @Override
                  public void run() {
                    if (target != null) {
                      launch(target);
                    } else {
                      view.showNotice(notice);
                    }
                  }
                });
          }
        });
  }

  /**
   * Collects the About runtime details on a worker, puts them on the clipboard and opens the
   * project's issue page in the OS browser.
   */
  void reportIssue() {
    final String toolkit = view.toolkitDescription();
    final SettingsLocation location = set.config().location();
    start(
        "Issue page not opened",
        new Runnable() {
          @Override
          public void run() {
            final AboutInfo info = AboutInfo.collect(toolkit, location);
            post(
                new Runnable() {
                  @Override
                  public void run() {
                    view.setText(String.join(System.lineSeparator(), info.details()));
                    if (view.launchUrl(AboutInfo.ISSUES_URL)) {
                      view.showNotice(UiText.NOTICE_ISSUE_DETAILS.text());
                    } else {
                      LOG.warn("No application opened the issue page");
                      view.showNotice(UiText.NOTICE_LAUNCH_FAILED.text());
                    }
                  }
                });
          }
        });
  }

  /**
   * Writes the user guide to the cache folder on a worker, then shows it in a browser window where
   * {@link WindowSet#embeddedGuide} allows one, else in the application the system opens HTML with,
   * else names the file in a notice.
   */
  void showGuide() {
    final Path settings = set.config().location().directory();
    final boolean embedded = set.embeddedGuide();
    start(
        "User guide not shown",
        new Runnable() {
          @Override
          public void run() {
            Path copy = null;
            String failure = null;
            try {
              copy = UserGuide.writeCopy(settings);
            } catch (IOException e) {
              failure =
                  UserGuide.included()
                      ? UiText.NOTICE_GUIDE_FAILED.format(LogText.oneLine(e.getMessage()))
                      : UiText.NOTICE_NO_DOCUMENTATION.text();
              LOG.warn("Cannot write the user guide: {}", LogText.oneLine(e.getMessage()));
            }
            final Path guide = copy;
            final String notice = failure;
            post(
                new Runnable() {
                  @Override
                  public void run() {
                    if (guide == null) {
                      view.showNotice(notice);
                    } else if (!embedded || !view.showGuide(guide)) {
                      showGuideElsewhere(guide);
                    }
                  }
                });
          }
        });
  }

  /**
   * Opens the user guide's copy in the application the system opens HTML with, else names the file
   * in a notice.
   *
   * @param guide the guide's copy
   */
  void showGuideElsewhere(Path guide) {
    if (closing.getAsBoolean()) {
      return;
    }
    if (!view.launch(guide)) {
      LOG.warn("No application opened {}", LogText.oneLine(guide));
      view.showNotice(UiText.NOTICE_GUIDE_AT.format(guide));
    }
  }

  /**
   * Opens a file or folder in the application the system associates with it; a failure shows a
   * notice.
   *
   * @param target the file or folder
   */
  void launch(Path target) {
    if (!view.launch(target)) {
      LOG.warn("No application opened {}", LogText.oneLine(target));
      view.showNotice(UiText.NOTICE_LAUNCH_FAILED.text());
    }
  }

  /** Starts a {@code LIFECYCLE} worker; when workers are closed, logs {@code refused} at DEBUG. */
  private void start(String refused, Runnable task) {
    if (!workers.tryStart(Workers.Kind.LIFECYCLE, task)) {
      LOG.debug("{}: workers are closed", LogText.oneLine(refused));
    }
  }

  /** Posts a view update that runs only while the window is open. */
  private void post(final Runnable update) {
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (!closing.getAsBoolean()) {
              update.run();
            }
          }
        });
  }
}
