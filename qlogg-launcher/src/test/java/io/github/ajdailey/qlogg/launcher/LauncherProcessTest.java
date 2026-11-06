package io.github.ajdailey.qlogg.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.Workers;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Launches as real processes: the lock's contract is between processes. */
class LauncherProcessTest {
  private static final long BOUND_SECONDS = 60;
  private static final String END = "end of output";
  private static final String PRIMARY = "PRIMARY";
  private static final String HANDED = "HANDED";

  @TempDir Path temp;

  /** One {@link LaunchMain}. */
  private static final class Child {
    private final Process process;
    private final BufferedWriter in;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<String>();

    Child(Path directory, Workers workers) throws IOException {
      List<String> command = new ArrayList<String>();
      command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
      command.add("-XX:TieredStopAtLevel=1");
      command.add("-cp");
      command.add(System.getProperty("java.class.path"));
      command.add(LaunchMain.class.getName());
      command.add(directory.toString());
      process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      in =
          new BufferedWriter(
              new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
      workers.start(
          Workers.Kind.IPC,
          new Runnable() {
            @Override
            public void run() {
              readOutput();
            }
          });
    }

    private void readOutput() {
      try (BufferedReader out =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line = out.readLine();
        while (line != null) {
          lines.add(line);
          line = out.readLine();
        }
      } catch (IOException e) {
        lines.add(e.toString());
      }
      lines.add(END);
    }

    void send(String line) throws IOException {
      in.write(line);
      in.newLine();
      in.flush();
    }

    /** Returns the first line starting with {@code prefix}, without it; logs are skipped. */
    String await(String prefix) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS);
      while (true) {
        String line = lines.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        if (line == null || END.equals(line)) {
          return "missing: " + prefix;
        }
        if (line.startsWith(prefix)) {
          return line.substring(prefix.length());
        }
      }
    }

    void end() throws InterruptedException {
      process.destroyForcibly();
      assertTrue(process.waitFor(BOUND_SECONDS, TimeUnit.SECONDS));
    }
  }

  /** A document on the lock file would close a channel on it, at the latest with its tab. */
  @Test
  void aPrimaryAskedToOpenTheLockFileKeepsTheLock() throws Exception {
    assertRouteKeepsTheLock("tab");
  }

  @Test
  void aPrimaryAskedToImportFiltersFromTheLockFileKeepsTheLock() throws Exception {
    assertRouteKeepsTheLock("filterImport");
  }

  @Test
  void aPrimaryAskedToImportHighlightersFromTheLockFileKeepsTheLock() throws Exception {
    assertRouteKeepsTheLock("highlightImport");
  }

  /** A write replaces the name's file, and the lock stays on the old one. */
  @Test
  void aPrimaryAskedToExportOverTheLockFileKeepsTheLock() throws Exception {
    assertRouteKeepsTheLock("export");
  }

  private void assertRouteKeepsTheLock(String route) throws Exception {
    try (Workers workers = new Workers(1, new Launcher.FailureLogger())) {
      Child primary = new Child(temp, workers);
      Child second = new Child(temp, workers);
      try {
        assertEquals("", primary.await(LaunchMain.READY));
        primary.send("");
        assertEquals(PRIMARY, primary.await(LaunchMain.OUTCOME));
        primary.send(LaunchMain.ROUTE + route);
        assertEquals(route, primary.await(LaunchMain.DONE));
        assertEquals("", second.await(LaunchMain.READY));
        second.send("");
        assertEquals(HANDED, second.await(LaunchMain.OUTCOME));
        primary.send("");
        assertEquals("1", primary.await(LaunchMain.RECEIVED));
      } finally {
        second.end();
        primary.end();
      }
    }
  }
}
