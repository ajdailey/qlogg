package io.github.ajdailey.qlogg.launcher;

import io.github.ajdailey.qlogg.app.cli.CommandLine;
import io.github.ajdailey.qlogg.app.cli.CommandLineException;
import io.github.ajdailey.qlogg.app.cli.CommandLineParser;
import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.cli.StartupRequest;
import io.github.ajdailey.qlogg.app.cli.Version;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.ipc.InstanceClient;
import io.github.ajdailey.qlogg.app.ipc.InstanceServer;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import io.github.ajdailey.qlogg.core.text.LogText;
import io.github.ajdailey.qlogg.core.util.os.OsTraits;
import io.github.ajdailey.qlogg.ui.swt.OpenDocuments;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses the command line, configures logging, resolves the settings directory and loads the
 * settings, configures the platform, runs the single-instance handoff, opens the debug log file
 * with {@code -l} or {@code log.file}, then hands over to {@link Gui}. All of it runs before the
 * Display exists, so the UI thread never waits on it. Exit statuses: 0 normal or files handed to
 * the running instance, 1 bad command line or settings directory, 3 SWT failed to load, 4 UI-thread
 * failures quit the application ({@link UiFailures}).
 *
 * <p>Without {@code -m}, a launch that takes the instance lock becomes the primary and {@link Gui}
 * accepts handoffs; otherwise it hands its files to the primary, waiting for a primary still
 * starting, and exits 0. Any handoff failure, or a settings directory another user owns or can
 * write, logs one WARN and the launch opens its own window, as with {@code -m}. A handing launch
 * without file arguments first collects the files the platform sends as open-document events
 * ({@link OpenDocuments}); a primary receives them through {@code AppMenu}.
 *
 * <p>Records logged after the command line parses and before the debug log file opens are kept
 * ({@link LogConfigurator#holdForFile}) and written into the file first; a launch that hands off or
 * opens no file discards them.
 */
public final class Launcher {
  static final int EXIT_USAGE = 1;
  static final int EXIT_SWT = 3;
  static final int EXIT_UI_FAILURE = 4;

  private static final Logger LOG = LoggerFactory.getLogger(Launcher.class);

  private final PrintStream out;
  private final PrintStream err;

  /**
   * Creates the launcher.
   *
   * @param out receives help and version text
   * @param err receives command-line errors
   */
  public Launcher(PrintStream out, PrintStream err) {
    this.out = out;
    this.err = err;
  }

  /**
   * Runs QLogg to completion. A nonzero status is returned before the Display exists, or after
   * {@link UiFailures} ran the quit.
   *
   * @param args the command line
   * @return the exit status
   */
  public int run(String[] args) {
    return run(args, List.of());
  }

  /**
   * Runs QLogg to completion. After a wildcard expansion the launch opens no command-line file
   * ({@link UnopenedNames}). Names it does not open are logged at INFO and shown in a message in
   * this launch's window, which opens beside a running instance rather than handing off to it.
   *
   * @param args the command line
   * @param expanded the patterns the Java launcher expanded in {@code args}
   * @return the exit status
   */
  public int run(String[] args, List<String> expanded) {
    CommandLine line;
    try {
      line = new CommandLineParser(Path.of("").toAbsolutePath()).parse(args);
    } catch (CommandLineException e) {
      err.println("qlogg: " + e.getMessage());
      err.println("Try 'qlogg --help'.");
      return EXIT_USAGE;
    }
    if (line.action() == CommandLine.Action.HELP) {
      out.print(CommandLineParser.usage());
      return 0;
    }
    if (line.action() == CommandLine.Action.VERSION) {
      out.println("qlogg " + Version.get());
      return 0;
    }
    LogConfigurator.setLevel(line.logLevel());
    LogConfigurator.holdForFile();
    try {
      return start(line, new UnopenedNames(expanded, line.unpassed()));
    } finally {
      LogConfigurator.endHold();
    }
  }

  /** Runs the launch after the command line parsed; ends the log hold before {@link Gui}. */
  private int start(CommandLine line, UnopenedNames unopened) {
    Thread.setDefaultUncaughtExceptionHandler(new FailureLogger());
    StartupRequest request = unopened.apply(line.request());
    String message = unopened.message();
    if (message != null && LOG.isInfoEnabled()) {
      LOG.info("{}", LogText.oneLine(message));
    }
    if (LOG.isDebugEnabled()) {
      for (FileRequest file : request.files()) {
        LOG.debug("Command-line file: {}", LogText.oneLine(file.path()));
      }
    }
    Path settings = null;
    if (request.settingsDirectory() != null) {
      try {
        settings = SafePaths.settingsDirectory(request.settingsDirectory());
      } catch (IOException e) {
        err.println("qlogg: --settings: " + LogText.oneLine(e.getMessage()));
        return EXIT_USAGE;
      }
    }
    SettingsLocation location = SettingsLocation.fromSystem(settings);
    PlatformSetup.apply(OsTraits.current(), location);
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "QLogg {} on Java {} from {}",
          LogText.oneLine(Version.get()),
          LogText.oneLine(Runtime.version()),
          LogText.oneLine(System.getProperty("java.home")));
    }
    ConfigStore config = new ConfigStore(location);
    Preferences preferences = config.load();
    InstanceServer server = null;
    if (!request.multi()) {
      showsMessage = message != null;
      Handoff handoff = handOff(location.directory(), request);
      if (handoff == Handoff.HANDED) {
        return 0;
      }
      server = handoff == Handoff.PRIMARY ? primary : null;
    }
    try {
      FileLog logFile =
          new FileLog(
              location.directory(),
              FileLog.fileName(server != null, ProcessHandle.current().pid()));
      if (line.logToFile() || preferences.options().debugLogFile()) {
        try {
          logFile.attach();
        } catch (IOException e) {
          LOG.warn("Cannot open the debug log file: {}", LogText.oneLine(e.getMessage()));
        }
      }
      LogConfigurator.endHold();
      return new Gui(request, config, preferences, err, null, server, logFile)
          .withMessage(message)
          .run();
    } catch (IOException e) {
      err.println("qlogg: " + LogText.oneLine(e.getMessage()));
      return EXIT_USAGE;
    } finally {
      if (server != null) {
        server.close();
      }
    }
  }

  /**
   * Returns the files the platform sent as open-document events, through {@link OpenDocuments}; a
   * Display that fails to start gives none.
   */
  private static List<FileRequest> openedDocuments() {
    List<String> texts;
    try {
      texts = OpenDocuments.await();
    } catch (SWTError | SWTException | LinkageError e) {
      LOG.warn("Open-document events not read: {}", LogText.oneLine(String.valueOf(e)));
      return List.of();
    }
    List<FileRequest> files = new ArrayList<FileRequest>();
    for (String text : texts) {
      try {
        files.add(new FileRequest(Path.of(text), 0));
      } catch (InvalidPathException e) {
        LOG.warn("Cannot open {}: {}", LogText.oneLine(text), LogText.oneLine(e.getMessage()));
      }
    }
    return files;
  }

  /** What the single-instance step decided. */
  enum Handoff {
    /** The files went to the running instance. */
    HANDED,
    /** This launch is the primary; {@link #primary} holds the listener. */
    PRIMARY,
    /** This launch runs on its own, as with {@code -m}. */
    ALONE
  }

  private InstanceServer primary;

  /** True when this launch has a message about names it did not open; {@link #handOff} reads it. */
  private boolean showsMessage;

  /**
   * Returns the listener of a launch that became the primary.
   *
   * @return the listener, or null
   */
  InstanceServer primary() {
    return primary;
  }

  /**
   * Becomes the primary instance, or hands the files to it. A launch with a message about names it
   * did not open runs {@link Handoff#ALONE} beside a running primary instead, so the message shows
   * in its own window.
   */
  Handoff handOff(Path directory, StartupRequest request) {
    try {
      SafePaths.settingsDirectory(directory);
    } catch (IOException e) {
      LOG.warn("Single-instance handoff off: {}", LogText.oneLine(e.getMessage()));
      return Handoff.ALONE;
    }
    try {
      primary = InstanceServer.claim(directory);
      if (primary != null) {
        return Handoff.PRIMARY;
      }
      if (showsMessage) {
        LOG.info("An instance is running; this launch opens its own window for its message");
        return Handoff.ALONE;
      }
      List<FileRequest> files = request.files().isEmpty() ? openedDocuments() : request.files();
      InstanceClient.handOff(directory, files, request.follow());
      if (LOG.isInfoEnabled()) {
        LOG.info("Handed {} files to the running instance", files.size());
      }
      return Handoff.HANDED;
    } catch (IOException e) {
      LOG.warn(
          "Handoff to the running instance failed; opening a window: {}",
          LogText.oneLine(e.getMessage()));
      return Handoff.ALONE;
    }
  }

  /** Logs a failure that escaped a task or a thread. */
  static final class FailureLogger implements Thread.UncaughtExceptionHandler {
    @Override
    public void uncaughtException(Thread thread, Throwable error) {
      LOG.error("Unhandled failure on thread {}", LogText.oneLine(thread.getName()), error);
    }
  }
}
