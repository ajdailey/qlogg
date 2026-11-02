package io.github.ajdailey.qlogg.launcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.config.PositionStore;
import io.github.ajdailey.qlogg.app.config.SessionStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * SIGTERM to a running QLogg process that restored a session of two files: the shutdown hook runs
 * the quit, which saves the session and the positions and removes the handoff endpoint, and the
 * process ends within the hook's bound. The session is saved only by a run that restored it, so the
 * run starts without files from a session file dated in the past. Linux only: {@code
 * Process.destroy} sends SIGTERM there, while on Windows it terminates the process without running
 * hooks.
 */
@Tag("swt")
@EnabledOnOs(OS.LINUX)
class SigtermTest {
  private static final long START_NANOS = TimeUnit.SECONDS.toNanos(60);
  private static final long EXIT_MARGIN_MILLIS = 2000;

  @TempDir Path temp;

  @Test
  void sigtermSavesTheSessionAndRemovesTheEndpoint() throws Exception {
    String java = ProcessHandle.current().info().command().orElse(null);
    if (java == null) {
      return;
    }
    // Owner-only: the handoff stays off in a directory other users can write.
    Path settings =
        Files.createDirectory(
            temp.resolve("settings"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    Path home = Files.createDirectories(temp.resolve("home"));
    Path first = write("first.log");
    Path second = write("second.log");
    Path sessionFile = settings.resolve(SessionStore.FILE);
    Files.writeString(
        sessionFile,
        "window.0.active=0\nwindow.0.tab.0.path="
            + first
            + "\nwindow.0.tab.1.path="
            + second
            + "\n",
        StandardCharsets.ISO_8859_1);
    FileTime past = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));
    Files.setLastModifiedTime(sessionFile, past);
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
    Path endpoint = settings.resolve("instance.endpoint");
    Path log = settings.resolve("logs").resolve("qlogg.log");
    try {
      long deadline = System.nanoTime() + START_NANOS;
      while (!(Files.exists(endpoint) && indexed(log, "first.log") && indexed(log, "second.log"))
          && child.isAlive()
          && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      assertTrue(child.isAlive(), "QLogg ended before SIGTERM");
      assertTrue(Files.exists(endpoint), "no endpoint; log: " + read(log));
      assertTrue(indexed(log, "second.log"), "files not indexed");
      long sent = System.nanoTime();
      child.destroy();
      assertTrue(
          child.waitFor(ShutdownHook.BOUND.toMillis() + EXIT_MARGIN_MILLIS, TimeUnit.MILLISECONDS),
          "not ended within the hook's bound");
      System.out.println(
          "SIGTERM to exit: " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sent) + " ms");
    } finally {
      child.destroyForcibly();
      child.waitFor(10, TimeUnit.SECONDS);
    }
    assertFalse(Files.exists(endpoint), "endpoint left behind");
    assertTrue(Files.getLastModifiedTime(sessionFile).compareTo(past) > 0, "session not saved");
    String session = Files.readString(sessionFile, StandardCharsets.ISO_8859_1);
    assertTrue(session.contains("first.log") && session.contains("second.log"), session);
    assertTrue(Files.isRegularFile(settings.resolve(PositionStore.FILE)));
  }

  private static String read(Path log) throws IOException {
    return Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "none";
  }

  private static boolean indexed(Path log, String name) throws IOException {
    return Files.exists(log)
        && Files.readString(log, StandardCharsets.UTF_8).contains("Indexing " + name + " done");
  }

  private Path write(String name) throws IOException {
    Path file = temp.resolve(name);
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 1000; i++) {
      text.append("line ").append(i).append('\n');
    }
    Files.writeString(file, text, StandardCharsets.UTF_8);
    return file;
  }
}
