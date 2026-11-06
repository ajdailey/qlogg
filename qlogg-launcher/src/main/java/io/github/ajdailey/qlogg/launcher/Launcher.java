package io.github.ajdailey.qlogg.launcher;

import io.github.ajdailey.qlogg.app.cli.CommandLine;
import io.github.ajdailey.qlogg.app.cli.CommandLineException;
import io.github.ajdailey.qlogg.app.cli.CommandLineParser;
import io.github.ajdailey.qlogg.app.cli.StartupRequest;
import io.github.ajdailey.qlogg.app.cli.Version;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.ipc.InstanceClient;
import io.github.ajdailey.qlogg.app.ipc.InstanceServer;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import io.github.ajdailey.qlogg.core.text.LogText;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses the command line, configures logging, resolves the settings directory and loads the
 * settings, configures the platform, runs the single-instance handoff, opens the debug log file
 * with {@code -l} or {@code log.file}, then hands over to {@link Gui}. All of it runs before the
 * Display exists, so the UI thread never waits on it. Exit statuses: 0 normal or files handed to
 * the running instance, 1 bad command line or settings directory, 3 SWT failed to load.
 *
 * <p>Without {@code -m}, a launch that takes the instance lock becomes the primary and {@link Gui}
 * accepts handoffs; otherwise it hands its files to the primary, waiting for a primary still
 * starting, and exits 0. Any handoff failure, or a settings directory another user owns or can
 * write, logs one WARN and the launch opens its own window, as with {@code -m}.
 */
public final class Launcher {
  static final int EXIT_USAGE = 1;
  static final int EXIT_SWT = 3;

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
   * Runs QLogg to completion. A nonzero status is returned only before the Display exists.
   *
   * @param args the command line
   * @return the exit status
   */
  public int run(String[] args) {
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
    Thread.setDefaultUncaughtExceptionHandler(new FailureLogger());
    StartupRequest request = line.request();
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
    PlatformSetup.apply(System.getProperty("os.name"), location);
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
      return new Gui(request, config, preferences, err, null, server, logFile).run();
    } catch (IOException e) {
      err.println("qlogg: " + LogText.oneLine(e.getMessage()));
      return EXIT_USAGE;
    } finally {
      if (server != null) {
        server.close();
      }
    }
  }

  /** What the single-instance step decided. */
  private enum Handoff {
    /** The files went to the running instance. */
    HANDED,
    /** This launch is the primary; {@link #primary} holds the listener. */
    PRIMARY,
    /** This launch runs on its own, as with {@code -m}. */
    ALONE
  }

  private InstanceServer primary;

  /** Becomes the primary instance, or hands the files to it. */
  private Handoff handOff(Path directory, StartupRequest request) {
    try {
      SafePaths.settingsDirectory(directory);
    } catch (IOException e) {
      LOG.warn("Single-instance handoff off: {}", LogText.oneLine(e.getMessage()));
      return Handoff.ALONE;
    }
    String refusal = InstanceClient.refusal(directory, System.getProperty("user.name"));
    if (refusal != null) {
      LOG.warn("Single-instance handoff off: {}", LogText.oneLine(refusal));
      return Handoff.ALONE;
    }
    try {
      primary = InstanceServer.claim(directory);
      if (primary != null) {
        return Handoff.PRIMARY;
      }
      InstanceClient.handOff(directory, request.files(), request.follow());
      if (LOG.isInfoEnabled()) {
        LOG.info("Handed {} files to the running instance", request.files().size());
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
