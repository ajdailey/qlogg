package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.AtomicFiles;
import io.github.ajdailey.qlogg.core.model.LineReader;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.RowCursor;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.text.LogText;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the text of a copy, save or selected-text read on the calling worker. Stateless. Each
 * method holds a {@link LogDocument#enter} entry while it reads, checks its {@link
 * CancellationToken} before every row and before {@link AtomicFiles#writeUserFile}, and returns an
 * empty result once canceled, so a canceled save writes no file. A file shorter than the index
 * snapshot after the rows are read fails the export: a read past the end returns less text, not an
 * error.
 *
 * <p>A copy is charged to {@link MemoryShare#COPY} at {@link #COPY_BYTES_PER_FILE_BYTE} per
 * selected file byte, a save at {@link #SAVE_BYTES_PER_FILE_BYTE}; a selection the share cannot
 * hold is refused. The caller releases the charge the returned {@link Result} carries; an exception
 * thrown out of {@link #export} releases it first.
 */
final class TextExporter {
  /**
   * Heap charged per selected file byte while copying: the builder and the finished string, each at
   * most two bytes per char, and a file byte decodes to at most one char.
   */
  static final int COPY_BYTES_PER_FILE_BYTE = 4;

  /** Heap charged per file byte while saving: the text as for copy plus its UTF-8 bytes. */
  static final int SAVE_BYTES_PER_FILE_BYTE = COPY_BYTES_PER_FILE_BYTE + 3;

  /** Bytes counted per row for a line number and a tab: 19 digits, a sign and the tab. */
  static final int NUMBER_BYTES = 21;

  /** Most chars of selected text taken for a search or quick find. */
  static final int MAX_SELECTED_CHARS = 4096;

  private static final Logger LOG = LoggerFactory.getLogger(TextExporter.class);

  /** Where built text goes. */
  enum Output {
    /** The clipboard. */
    CLIPBOARD,
    /** The clipboard, each line after its number and a tab. */
    NUMBERED,
    /** A {@link TextListener}. */
    LISTENER,
    /** A file, written by {@link #export}. */
    FILE
  }

  /**
   * One copy, save or text read.
   *
   * @param selection the rows and columns taken; an empty one takes its caret's whole row
   * @param block true for the display columns between anchor and caret on every row
   * @param output where the text goes
   * @param file the validated target for {@link Output#FILE}, else null
   */
  record Request(Selection selection, boolean block, Output output, Path file) {}

  /**
   * The rows of a request in the view.
   *
   * @param walk the view's lines
   * @param first the first row
   * @param last the last row, at or after {@code first}
   * @param firstLine the source line of {@code first}
   */
  record Rows(RowCursor walk, long first, long last, long firstLine) {}

  /**
   * What an export produced. Text and refusal are both null when the export was canceled, the
   * document closed, or the entry was refused.
   *
   * @param text the built text, or null
   * @param refusal the error message to show, or null
   * @param charged bytes reserved from {@link MemoryShare#COPY}; the caller releases them
   * @param rows rows taken
   * @param cutLines for a save of whole lines, the lines longer than {@link
   *     LineReader#MAX_LINE_BYTES}, of which it holds the first part
   */
  record Result(String text, String refusal, long charged, long rows, long cutLines) {
    /** Nothing built and nothing charged. */
    static final Result NONE = new Result(null, null, 0, 0);

    /** Creates a result with no cut lines. */
    Result(String text, String refusal, long charged, long rows) {
      this(text, refusal, charged, rows, 0);
    }
  }

  /** The rows {@link #appendText} took and the lines it counted as cut. */
  private record Appended(long rows, long cutLines) {}

  private TextExporter() {}

  /**
   * Builds the text of the rows and, for {@link Output#FILE}, writes it as UTF-8 through {@link
   * AtomicFiles}.
   *
   * @param document the document read
   * @param index the index snapshot the rows were mapped with
   * @param rows the rows taken
   * @param request what to build
   * @param budget charged for the text
   * @param token checked before each row and before the write
   * @return the result; its charge is the caller's to release
   */
  @WorkerThread
  static Result export(
      LogDocument document,
      LineIndex index,
      Rows rows,
      Request request,
      MemoryBudget budget,
      CancellationToken token) {
    if (!document.enter()) {
      return Result.NONE;
    }
    Output output = request.output();
    int perByte = output == Output.FILE ? SAVE_BYTES_PER_FILE_BYTE : COPY_BYTES_PER_FILE_BYTE;
    long charged = 0;
    Result result = null;
    try {
      long last = index.lineCount() - 1;
      long bytes = selectedBytes(index, rows, Math.min(rows.firstLine(), last), token);
      if (output == Output.NUMBERED) {
        bytes += (rows.last() - rows.first() + 1) * NUMBER_BYTES;
      }
      if (bytes > maxCopyBytes(Long.MAX_VALUE)
          || !budget.reserve(MemoryShare.COPY, bytes * perByte)) {
        UiText pattern = output == Output.FILE ? UiText.SAVE_TOO_LARGE : UiText.COPY_TOO_LARGE;
        String refusal =
            pattern.format(
                mebibytes(bytes), mebibytes(budget.available(MemoryShare.COPY) / perByte));
        result = new Result(null, refusal, 0, 0);
      } else {
        charged = bytes * perByte;
        // Sized once: a doubling builder peaks above the charge.
        StringBuilder out = new StringBuilder((int) bytes);
        Appended taken = appendText(document.newReader(), index, rows, request, out, token);
        if (document.bytes().size() < index.indexedBytes()) {
          throw new IOException(UiText.EXPORT_FILE_SHORTER.text());
        }
        String text = out.toString();
        if (output == Output.FILE) {
          token.check();
          AtomicFiles.writeUserFile(request.file(), text.getBytes(StandardCharsets.UTF_8));
        }
        result = new Result(text, null, charged, taken.rows(), taken.cutLines());
      }
    } catch (CancellationException | ClosedChannelException e) {
      result = new Result(null, null, charged, 0);
    } catch (IOException | UncheckedIOException e) {
      String reason = LogText.oneLine(e.getMessage());
      String refusal =
          output == Output.FILE
              ? UiText.SAVE_FAILED.format(reason)
              : UiText.COPY_READ_FAILED.format(reason);
      result = new Result(null, refusal, charged, 0);
    } finally {
      document.exit();
      if (result == null) {
        budget.release(MemoryShare.COPY, charged);
      }
    }
    return result;
  }

  /**
   * Reads the selected columns of one line, cut at {@link #MAX_SELECTED_CHARS}.
   *
   * @param document the document read
   * @param index the index snapshot
   * @param line a 0-based line
   * @param from the first display column
   * @param to the display column after the last
   * @param token checked before the read
   * @return the text, or null when canceled, closed or unreadable
   */
  @WorkerThread
  static String selectedText(
      LogDocument document,
      LineIndex index,
      long line,
      long from,
      long to,
      CancellationToken token) {
    if (token.isCanceled() || !document.enter()) {
      return null;
    }
    try {
      StringBuilder out = new StringBuilder();
      document.newReader().appendText(index, line, from, to, out);
      return out.length() > MAX_SELECTED_CHARS
          ? out.substring(0, MAX_SELECTED_CHARS)
          : out.toString();
    } catch (ClosedChannelException e) {
      return null;
    } catch (IOException | UncheckedIOException e) {
      LOG.warn(
          "Cannot read {}: {}", LogText.oneLine(document.path()), LogText.oneLine(e.getMessage()));
      return null;
    } finally {
      document.exit();
    }
  }

  /**
   * Returns the heap charged to copy a selection.
   *
   * @param fileBytes the selection's size in the file
   * @return bytes charged to {@link MemoryShare#COPY}
   */
  static long copyCost(long fileBytes) {
    return fileBytes * COPY_BYTES_PER_FILE_BYTE;
  }

  /**
   * Returns the largest selection a charge covers. A Java string holds at most {@code
   * Integer.MAX_VALUE} chars, which caps it further.
   *
   * @param chargeBytes bytes the share can hold
   * @return file bytes
   */
  static long maxCopyBytes(long chargeBytes) {
    return Math.min(chargeBytes / COPY_BYTES_PER_FILE_BYTE, Integer.MAX_VALUE - 8);
  }

  /** Appends the selected text of the rows. */
  @WorkerThread
  private static Appended appendText(
      LineReader copier,
      LineIndex index,
      Rows rows,
      Request request,
      StringBuilder out,
      CancellationToken token)
      throws IOException {
    Selection copied = request.selection();
    long last = index.lineCount() - 1;
    long fromColumn = copied.isEmpty() ? 0 : copied.start().column();
    long toColumn = copied.isEmpty() ? Long.MAX_VALUE : copied.end().column();
    long left = Math.min(copied.anchor().column(), copied.caret().column());
    long right = Math.max(copied.anchor().column(), copied.caret().column());
    String separator = request.block() ? "\n" : System.lineSeparator();
    long line = Math.min(rows.firstLine(), last);
    boolean countCut = request.output() == Output.FILE && !request.block();
    long taken = 0;
    long cut = 0;
    for (long row = rows.first(); row <= rows.last() && line >= 0 && line <= last; row++) {
      token.check();
      if (countCut && index.lineEnd(line) - index.lineStart(line) > LineReader.MAX_LINE_BYTES) {
        cut++;
      }
      if (request.output() == Output.NUMBERED) {
        out.append(line + 1).append('\t');
      }
      if (request.block()) {
        copier.appendColumns(index, line, left, right, out);
      } else {
        long from = row == rows.first() ? fromColumn : 0;
        long to = row == rows.last() ? toColumn : Long.MAX_VALUE;
        copier.appendText(index, line, from, to, out);
      }
      if (row != rows.last()) {
        out.append(separator);
      }
      taken++;
      line = rows.walk().next(line);
    }
    return new Appended(taken, cut);
  }

  /** File bytes of the rows: one span in the main view, a sum over listed lines else. */
  private static long selectedBytes(
      LineIndex index, Rows rows, long firstLine, CancellationToken token) {
    long last = index.lineCount() - 1;
    if (rows.walk().contiguous()) {
      long lastLine = Math.min(rows.last(), last);
      return index.lineEnd(lastLine) - index.lineStart(firstLine);
    }
    long bytes = 0;
    long line = firstLine;
    for (long row = rows.first(); row <= rows.last() && line >= 0 && line <= last; row++) {
      token.check();
      bytes += index.lineEnd(line) - index.lineStart(line) + 1;
      line = rows.walk().next(line);
    }
    return bytes;
  }

  private static double mebibytes(long bytes) {
    return bytes / (double) (1L << 20);
  }
}
