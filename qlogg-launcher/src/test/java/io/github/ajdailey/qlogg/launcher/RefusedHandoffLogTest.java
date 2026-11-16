package io.github.ajdailey.qlogg.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.app.ipc.InstanceServer;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A real {@code -l} process whose handoff the endpoint check refuses while this JVM holds the
 * instance lock: the WARN, logged before the debug log file opens, is in that file once. Linux only
 * for {@code SigtermTest}'s reason: the ordered quit needs SIGTERM.
 */
@Tag("swt")
@EnabledOnOs(OS.LINUX)
class RefusedHandoffLogTest {
  private static final long START_NANOS = TimeUnit.SECONDS.toNanos(60);
  private static final long EXIT_MARGIN_MILLIS = 2000;
  private static final String WARN = "Handoff to the running instance failed; opening a window: ";

  @TempDir Path temp;

  @Test
  void aRefusedEndpointIsNamedInTheLogFile() throws Exception {
    String java = ProcessHandle.current().info().command().orElse(null);
    assumeTrue(java != null, "no java command");
    Path settings = SafePaths.settingsDirectory(temp.resolve("settings"));
    Path home = Files.createDirectories(temp.resolve("home"));
    try (InstanceServer held = InstanceServer.claim(settings)) {
      assertNotNull(held);
      Files.setPosixFilePermissions(
          settings.resolve("instance.endpoint"), PosixFilePermissions.fromString("rw-r--r--"));
      Process child =
          new ProcessBuilder(
                  java,
                  "-Duser.home=" + home,
                  "-cp",
                  System.getProperty("java.class.path"),
                  Main.class.getName(),
                  "--settings",
                  settings.toString(),
                  "-l")
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      Path log = settings.resolve("logs").resolve("qlogg-" + child.pid() + ".log");
      String refusal = WARN + "the endpoint file is open to other accounts";
      try {
        long deadline = System.nanoTime() + START_NANOS;
        while (!(read(log).contains("Main window open") && read(log).contains(refusal))
            && child.isAlive()
            && System.nanoTime() < deadline) {
          Thread.sleep(50);
        }
        assertTrue(read(log).contains("Main window open"), "no window; log: " + read(log));
        assertTrue(read(log).contains(refusal), "no refusal; log: " + read(log));
        child.destroy();
        assertTrue(
            child.waitFor(
                ShutdownHook.BOUND.toMillis() + EXIT_MARGIN_MILLIS, TimeUnit.MILLISECONDS),
            "not ended within the hook's bound");
      } finally {
        child.destroyForcibly();
        child.waitFor(10, TimeUnit.SECONDS);
      }
      String text = read(log);
      assertEquals(1, text.split(WARN, -1).length - 1, text);
    }
  }

  private static String read(Path file) throws IOException {
    return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
  }
}
