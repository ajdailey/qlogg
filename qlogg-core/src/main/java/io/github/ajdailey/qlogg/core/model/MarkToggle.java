package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.CpuSteps;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CancellationException;

/**
 * Toggles the marks of consecutive rows of a view: when every row's line is marked they are all
 * unmarked, otherwise all are marked. Runs on a worker in batches of {@link #BATCH} lines, each a
 * CPU step when given {@link Workers} and each under one write lock of the marks, so readers on
 * other threads see the marks grow or shrink batch by batch. Marking reserves its storage from the
 * marks' budget and stops at the first batch the share refuses; lines marked before stay marked.
 * Unmarking also removes the lines' {@link MarkComments}.
 */
public final class MarkToggle {
  /** Lines per batch. */
  public static final int BATCH = 4096;

  /**
   * How a toggle ended.
   *
   * @param marked true when the rows were marked, false when unmarked
   * @param changed lines whose mark changed
   * @param canceled true when the token stopped it
   * @param memoryLimit true when the budget refused a batch
   */
  public record Result(boolean marked, long changed, boolean canceled, boolean memoryLimit) {}

  /** Receives progress on the worker. */
  public interface Progress {
    /**
     * Rows were processed.
     *
     * @param done rows so far, both passes counted
     * @param total rows of both passes
     */
    void progressed(long done, long total);
  }

  private MarkToggle() {}

  /**
   * Toggles the marks of {@code count} rows starting at the row whose line is {@code firstLine}.
   *
   * @param marks the document's marks
   * @param rows walks the view's lines
   * @param firstLine the first row's line
   * @param count rows, at least 0
   * @param token checked per batch
   * @param progress receives progress per batch, or null
   * @return how it ended
   */
  public static Result run(
      MutableLineSet marks,
      RowCursor rows,
      long firstLine,
      long count,
      CancellationToken token,
      Progress progress) {
    return run(marks, null, rows, firstLine, count, token, progress);
  }

  /**
   * Toggles marks as {@link #run(MutableLineSet, RowCursor, long, long, CancellationToken,
   * Progress)} does and removes the comments of the lines it unmarks.
   *
   * @param marks the document's marks
   * @param comments the marks' comments, or null
   * @param rows walks the view's lines
   * @param firstLine the first row's line
   * @param count rows, at least 0
   * @param token checked per batch
   * @param progress receives progress per batch, or null
   * @return how it ended
   */
  public static Result run(
      MutableLineSet marks,
      MarkComments comments,
      RowCursor rows,
      long firstLine,
      long count,
      CancellationToken token,
      Progress progress) {
    return run(null, marks, comments, rows, firstLine, count, token, progress);
  }

  /**
   * Toggles marks as {@link #run(MutableLineSet, MarkComments, RowCursor, long, long,
   * CancellationToken, Progress)} does, one batch per CPU step.
   *
   * @param cpu runs the steps ({@link CpuSteps#run}), or null to run them on the calling thread
   * @param marks the document's marks
   * @param comments the marks' comments, or null
   * @param rows walks the view's lines
   * @param firstLine the first row's line
   * @param count rows, at least 0
   * @param token checked per batch
   * @param progress receives progress per batch, or null
   * @return how it ended
   */
  public static Result run(
      Workers cpu,
      MutableLineSet marks,
      MarkComments comments,
      RowCursor rows,
      long firstLine,
      long count,
      CancellationToken token,
      Progress progress) {
    Toggle toggle = new Toggle(marks, comments, rows, firstLine, count, token, progress);
    try {
      CpuSteps.run(cpu, toggle);
    } catch (CancellationException e) {
      return new Result(toggle.mark, toggle.changed, true, false);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return toggle.result;
  }

  /**
   * The toggle between batches: first the pass that decides to mark (any row unmarked), then the
   * pass that applies it.
   */
  private static final class Toggle implements CpuSteps.Steps {
    private final MutableLineSet marks;
    private final MarkComments comments;
    private final RowCursor rows;
    private final long firstLine;
    private final long count;
    private final CancellationToken token;
    private final Progress progress;
    private final long[] batch = new long[BATCH];
    private final long total;
    private boolean deciding;
    private boolean mark;
    private long done;
    private long line;
    private long seen;
    private long changed;
    private Result result;

    Toggle(
        MutableLineSet marks,
        MarkComments comments,
        RowCursor rows,
        long firstLine,
        long count,
        CancellationToken token,
        Progress progress) {
      this.marks = marks;
      this.comments = comments;
      this.rows = rows;
      this.firstLine = firstLine;
      this.count = count;
      this.token = token;
      this.progress = progress;
      this.total = 2 * count;
      this.line = firstLine;
      if (rows.contiguous()) {
        mark = marks.rank(firstLine + count) - marks.rank(firstLine) < count;
        done = count;
      } else {
        deciding = true;
      }
    }

    @Override
    public boolean step() {
      if (deciding) {
        return decide();
      }
      if (seen >= count || line < 0) {
        result = new Result(mark, changed, false, false);
        return false;
      }
      if (token.isCanceled()) {
        result = new Result(mark, changed, true, false);
        return false;
      }
      int n = 0;
      while (n < BATCH && seen + n < count && line >= 0) {
        batch[n++] = line;
        line = rows.contiguous() ? line + 1 : rows.next(line);
      }
      if (mark) {
        long before = marks.size();
        if (!marks.addSorted(batch, n)) {
          result = new Result(true, changed, false, true);
          return false;
        }
        changed += marks.size() - before;
      } else {
        changed += marks.removeSorted(batch, n);
        if (comments != null) {
          comments.removeAll(batch, n);
        }
      }
      seen += n;
      report(progress, done + seen, total);
      return true;
    }

    /** One batch of the deciding pass; ends the pass at an unmarked line or the last row. */
    private boolean decide() {
      if (done < count && line >= 0 && !mark) {
        int n = 0;
        while (n < BATCH && done + n < count && line >= 0) {
          batch[n++] = line;
          line = rows.next(line);
        }
        for (int i = 0; i < n && !mark; i++) {
          mark = !marks.contains(batch[i]);
        }
        done += n;
        if (token.isCanceled()) {
          result = new Result(true, 0, true, false);
          return false;
        }
        report(progress, done, total);
        return true;
      }
      deciding = false;
      done = count;
      line = firstLine;
      return true;
    }
  }

  private static void report(Progress progress, long done, long total) {
    if (progress != null) {
      progress.progressed(done, total);
    }
  }
}
