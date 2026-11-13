package io.github.ajdailey.qlogg.launcher;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.cli.StartupRequest;
import io.github.ajdailey.qlogg.app.highlight.HighlightFile;
import io.github.ajdailey.qlogg.app.ipc.InstanceServer;
import io.github.ajdailey.qlogg.app.search.FilterFile;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.io.FileByteSource;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A child process for {@code LauncherProcessTest}: one launch through {@link Launcher#handOff}.
 * Prints {@link #READY}, waits for a line on standard input, then prints {@link #OUTCOME} and the
 * result. A {@code PRIMARY} takes launches. A line {@link #ROUTE} and a route name runs that route
 * on {@code instance.lock} and prints {@link #DONE} and the name; any other line or end of input
 * closes and prints {@link #RECEIVED} and the count.
 */
public final class LaunchMain {
  static final String READY = "ready";
  static final String OUTCOME = "outcome ";
  static final String RECEIVED = "received ";
  static final String ROUTE = "route ";
  static final String DONE = "done ";

  private LaunchMain() {}

  /**
   * Runs one launch.
   *
   * @param args the settings directory
   * @throws IOException when standard input fails
   */
  public static void main(String[] args) throws IOException {
    Path directory = Path.of(args[0]);
    BufferedReader in =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    say(READY);
    if (in.readLine() == null) {
      return;
    }
    StartupRequest request =
        new StartupRequest(
            List.of(new FileRequest(directory.resolve("mock.txt"), 0)),
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            false);
    Launcher launcher = new Launcher(System.out, System.err);
    Launcher.Handoff outcome = launcher.handOff(directory, request);
    say(OUTCOME + outcome);
    if (outcome != Launcher.Handoff.PRIMARY) {
      return;
    }
    final AtomicInteger received = new AtomicInteger();
    try (Workers workers = new Workers(1, new Launcher.FailureLogger());
        InstanceServer server = launcher.primary()) {
      server.start(workers);
      server.deliverTo(
          new InstanceServer.Receiver() {
            @Override
            public boolean deliver(List<FileRequest> files, boolean follow) {
              received.incrementAndGet();
              return true;
            }
          });
      String line = in.readLine();
      while (line != null && line.startsWith(ROUTE)) {
        String route = line.substring(ROUTE.length());
        run(route, directory.resolve("instance.lock"), server.heldLock());
        say(DONE + route);
        line = in.readLine();
      }
    }
    say(RECEIVED + received.get());
  }

  /** Runs one way a QLogg process reaches a file the user names, on the lock file. */
  private static void run(String route, Path lock, SafePaths.HeldLock held) {
    try {
      switch (route) {
        case "tab" -> FileByteSource.open(lock, held).close();
        case "filterImport" -> FilterFile.read(lock, held);
        case "highlightImport" -> HighlightFile.read(lock, held);
        case "export" -> FilterFile.write(lock, List.of(), held);
        default -> throw new IllegalArgumentException(route);
      }
    } catch (IOException e) {
      // An import of an empty file fails after the read; the close is what counts.
      say("failed " + route + ": " + e.getClass().getSimpleName());
    }
  }

  private static void say(String line) {
    System.out.println(line);
    System.out.flush();
  }
}
