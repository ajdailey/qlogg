package io.github.ajdailey.qlogg.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.cli.SessionMode;
import io.github.ajdailey.qlogg.app.cli.StartupRequest;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.SettingsLocation;
import io.github.ajdailey.qlogg.app.config.SettingsMode;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.model.Viewport;
import io.github.ajdailey.qlogg.core.testkit.Poll;
import io.github.ajdailey.qlogg.ui.swt.MainWindow;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opens a generated 3 GiB file of 64-byte lines through the composed application and navigates it:
 * the first lines show before indexing ends, go to line reaches the last line (past 2^31 bytes),
 * and a thumb drag lands on the mapped line. The file is written at test time and needs 4 GiB free
 * in the temporary directory; the test is skipped otherwise.
 */
@Tag("swt")
class LargeFileGuiTest {
  static final int LINE_BYTES = 64;
  static final long FILE_BYTES = 3L << 30;
  static final long LINES = FILE_BYTES / LINE_BYTES;
  private static final long BOUND_NANOS = TimeUnit.MINUTES.toNanos(5);

  @TempDir Path temp;

  /** Text of a 1-based line, without its LF. */
  static String line(long number) {
    String digits = String.format("%012d", number);
    StringBuilder text = new StringBuilder(LINE_BYTES);
    text.append(digits).append(" 2026-11-01 12:00:00.000 INFO  [worker-3] mock event flagword");
    while (text.length() < LINE_BYTES - 1) {
      text.append('.');
    }
    text.setLength(LINE_BYTES - 1);
    return text.toString();
  }

  /** Writes {@code lines} lines of {@link #line} text. */
  static void write(Path file, long lines) throws IOException {
    byte[] template = (line(0) + "\n").getBytes(StandardCharsets.US_ASCII);
    int perBuffer = 65536;
    ByteBuffer buffer = ByteBuffer.allocate(perBuffer * LINE_BYTES);
    try (FileChannel channel =
        FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      long number = 1;
      while (number <= lines) {
        buffer.clear();
        int count = (int) Math.min(perBuffer, lines - number + 1);
        for (int i = 0; i < count; i++) {
          int base = i * LINE_BYTES;
          buffer.put(base, template);
          long value = number + i;
          for (int d = 11; d >= 0; d--) {
            buffer.put(base + d, (byte) ('0' + value % 10));
            value /= 10;
          }
        }
        buffer.limit(count * LINE_BYTES);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        number += count;
      }
    }
  }

  @Test
  void opensAndNavigatesAMultiGigabyteFile() throws Exception {
    assumeTrue(Files.getFileStore(temp).getUsableSpace() > FILE_BYTES + (1L << 30));
    Path file = temp.resolve("large.log");
    long writeStart = System.nanoTime();
    write(file, LINES);
    assertEquals(FILE_BYTES, Files.size(file));
    long writeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - writeStart);

    StartupRequest request =
        new StartupRequest(
            List.of(new FileRequest(file, 0)),
            false,
            SessionMode.DEFAULT,
            1000,
            700,
            null,
            null,
            null,
            true);
    final List<String> observed = new ArrayList<String>();
    final Workers[] owned = new Workers[1];
    final long[] times = new long[3];
    Gui.Observer observer =
        new Gui.Observer() {
          @Override
          public void opened(
              final MainWindow window, final ApplicationController controller, Workers workers) {
            owned[0] = workers;
            final Display display = window.shell().getDisplay();
            final long start = System.nanoTime();
            final long deadline = start + BOUND_NANOS;
            display.timerExec(
                20,
                new Runnable() {
                  private int phase;

                  @Override
                  public void run() {
                    if (System.nanoTime() > deadline) {
                      observed.add("timed out in phase " + phase);
                      controller.shutdown();
                      return;
                    }
                    DocumentController document = controller.activeDocument();
                    if (document != null && step(window, document)) {
                      phase++;
                    }
                    if (phase == 4) {
                      controller.shutdown();
                    } else {
                      display.timerExec(20, this);
                    }
                  }

                  /** Returns true when the phase is done. */
                  private boolean step(MainWindow window, DocumentController document) {
                    Viewport viewport = document.viewport();
                    switch (phase) {
                      case 0:
                        if (!document.lines().contains(0)) {
                          return false;
                        }
                        times[0] = System.nanoTime() - start;
                        observed.add("first shown while indexing " + document.state().indexing());
                        observed.add(document.lines().text(0));
                        return true;
                      case 1:
                        if (document.document().status().indexing()
                            || viewport.lineCount() != LINES) {
                          return false;
                        }
                        times[1] = System.nanoTime() - start;
                        observed.add(window.positionText().contains("/50,331,648") + "");
                        document.goToLine(LINES);
                        return true;
                      case 2:
                        if (!document.lines().contains(LINES - 1)) {
                          return false;
                        }
                        times[2] = System.nanoTime() - start;
                        observed.add(document.lines().text(LINES - 1));
                        observed.add("caret " + document.selection().caret().line());
                        observed.add("bottom " + viewport.atBottom());
                        observed.add(
                            "bar "
                                + (viewport.scrollbarSelection()
                                    == viewport.scrollbarMaximum() - viewport.scrollbarThumb()));
                        document.setScrollbarSelection((int) (LINES / 2));
                        return true;
                      case 3:
                        if (!document.lines().contains(viewport.topLine())) {
                          return false;
                        }
                        observed.add("top " + viewport.topLine());
                        observed.add(document.lines().text(viewport.topLine()));
                        return true;
                      default:
                        return false;
                    }
                  }
                });
          }
        };
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    assertEquals(
        0,
        new Gui(
                request,
                new ConfigStore(
                    new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings"))),
                Preferences.DEFAULTS,
                err,
                observer)
            .run());
    System.out.printf(
        "LargeFileGuiTest: wrote %d bytes in %d ms; first lines %d ms, indexed %d ms,"
            + " last line shown %d ms after open%n",
        FILE_BYTES,
        writeMillis,
        TimeUnit.NANOSECONDS.toMillis(times[0]),
        TimeUnit.NANOSECONDS.toMillis(times[1]),
        TimeUnit.NANOSECONDS.toMillis(times[2]));
    assertEquals(
        List.of(
            "first shown while indexing true",
            line(1),
            "true",
            line(LINES),
            "caret " + (LINES - 1),
            "bottom true",
            "bar true",
            "top " + (LINES / 2),
            line(LINES / 2 + 1)),
        observed);
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return owned[0].liveThreads() == 0;
              }
            },
            Duration.ofSeconds(10)));
    Files.delete(file);
  }

  @Test
  void generatedLinesHaveFixedWidthAndNumber() throws Exception {
    Path file = temp.resolve("small.log");
    write(file, 70000);
    List<String> lines = Files.readAllLines(file, StandardCharsets.US_ASCII);
    assertEquals(70000, lines.size());
    assertEquals(line(1), lines.get(0));
    assertEquals(line(65537), lines.get(65536));
    assertTrue(lines.get(69999).startsWith("000000070000 "));
    assertEquals(LINE_BYTES - 1, lines.get(123).length());
  }
}
