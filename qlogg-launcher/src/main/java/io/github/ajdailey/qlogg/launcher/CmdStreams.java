package io.github.ajdailey.qlogg.launcher;

import io.github.ajdailey.qlogg.core.text.LogText;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Releases the standard output and error {@code qlogg.cmd} hands Java. Its {@code start /b} gives
 * Java the caller's handles, so a caller reading them through a pipe waits until every holder
 * closes them. Under {@code qlogg.cmd} ({@code -Dqlogg.launcher=cmd}) both handles close once the
 * window is open, and later output is discarded; {@code -v}, {@code -h} and command-line errors
 * print before that.
 */
final class CmdStreams {
  /** The system property {@code qlogg.cmd} sets. */
  static final String LAUNCHER = "qlogg.launcher";

  /** {@link #LAUNCHER}'s value under {@code qlogg.cmd}. */
  static final String CMD = "cmd";

  private static final Logger LOG = LoggerFactory.getLogger(CmdStreams.class);

  private CmdStreams() {}

  /** Closes both handles when this process runs under {@code qlogg.cmd}. */
  static void closeAfterWindow() {
    if (!CMD.equals(System.getProperty(LAUNCHER))) {
      return;
    }
    System.out.flush();
    System.err.flush();
    System.setOut(new PrintStream(OutputStream.nullOutputStream()));
    System.setErr(new PrintStream(OutputStream.nullOutputStream()));
    close(FileDescriptor.out);
    close(FileDescriptor.err);
    LOG.debug("Standard output and error closed for qlogg.cmd");
  }

  private static void close(FileDescriptor handle) {
    try {
      new FileOutputStream(handle).close();
    } catch (IOException e) {
      LOG.debug("Standard handle not closed: {}", LogText.oneLine(e.getMessage()));
    }
  }
}
