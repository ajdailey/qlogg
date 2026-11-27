package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.MarkComments;
import io.github.ajdailey.qlogg.core.rotate.LineMap;
import io.github.ajdailey.qlogg.core.rotate.RotatedIndex;
import io.github.ajdailey.qlogg.core.rotate.SetFiles;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.text.LogText;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves a tab's marks and their comments into a reopened document through a {@link LineMap}: after
 * a rotated set's roll-over ({@code ROLLED}), and when Include Rotated Files turns a tab into a set
 * or back into one file. Marks and comments on a dropped member are left out and counted.
 */
final class LineCarry {
  private static final Logger LOG = LoggerFactory.getLogger(LineCarry.class);
  private static final int BATCH = 4096;

  /**
   * The outcome of a copy.
   *
   * @param map the map the lines went through, or null when they kept their numbers
   * @param marksComplete false when the {@code RESULTS} share refused part of the marks or comments
   * @param droppedMarks marked lines left out with their member
   */
  record Carried(LineMap map, boolean marksComplete, long droppedMarks) {}

  private LineCarry() {}

  /**
   * Returns the path a reopen opens: the document's path, or a set's newest file when Include
   * Rotated Files turns it back into one file.
   *
   * @param document the tab's document
   * @param toggled true when the reopen changes between one file and a set
   * @return the path
   */
  static Path reopenPath(LogDocument document, boolean toggled) {
    SetFiles files = document.files();
    if (!toggled || files == null || files.members().isEmpty()) {
      return document.path();
    }
    return files.members().get(files.members().size() - 1).path();
  }

  /**
   * Copies a reopened tab's marks and comments: unchanged for a reload, through the set's map after
   * a roll-over or a toggle, and none after another change on disk. A toggle whose line count fails
   * logs a WARN and copies none.
   *
   * @param old the tab's document
   * @param document the reopened document
   * @param change the change on disk, or null for a reload
   * @param toggled true when the reopen changes between one file and a set
   * @param closing true ends a line count
   * @return the outcome
   */
  @WorkerThread
  static Carried carry(
      LogDocument old,
      LogDocument document,
      FileChange change,
      boolean toggled,
      BooleanSupplier closing) {
    if (change == FileChange.ROLLED && old.lineMap() != null) {
      return copy(old, document, old.lineMap());
    }
    if (change != null) {
      return new Carried(null, true, 0);
    }
    if (!toggled) {
      return copy(old, document, null);
    }
    try {
      return copy(old, document, toggled(old, document, closing));
    } catch (IOException e) {
      LOG.warn(
          "Marks of {} not kept: {}",
          LogText.oneLine(document.path()),
          LogText.oneLine(e.getMessage()));
      return new Carried(null, false, 0);
    }
  }

  /**
   * Builds the map from a tab's document to the same files reopened with Include Rotated Files
   * turned on or off: one file lands at its member's first line in the set; a set keeps the lines
   * of the member reopened alone. Blocking when {@code to} is a set: counts the lines of its
   * members before the last.
   *
   * @param from the tab's document
   * @param to the reopened document
   * @param closing true ends the count
   * @return the map
   * @throws IOException when counting fails
   */
  @WorkerThread
  static LineMap toggled(LogDocument from, LogDocument to, BooleanSupplier closing)
      throws IOException {
    LineIndex old = from.index();
    SetFiles files = to.files();
    if (files != null) {
      long[] bases = to.memberBases(closing);
      int member = memberOf(files, from.path());
      return new LineMap(
          List.of(new LineMap.Entry(from.path(), 0, old.lineCount(), bases[member])));
    }
    SetFiles before = from.files();
    if (!(old instanceof RotatedIndex rotated) || before == null) {
      return new LineMap(List.of(new LineMap.Entry(from.path(), 0, old.lineCount(), 0)));
    }
    int kept = memberOf(before, to.path());
    List<LineMap.Entry> entries = new ArrayList<LineMap.Entry>();
    long[] starts = rotated.memberStarts();
    for (int i = 0; i < starts.length; i++) {
      long end = i + 1 < starts.length ? starts[i + 1] : rotated.lineCount();
      int member = rotated.memberOf(starts[i]);
      entries.add(
          new LineMap.Entry(
              before.members().get(member).path(),
              starts[i],
              end - starts[i],
              member == kept ? 0 : LineMap.DROPPED));
    }
    return new LineMap(entries);
  }

  /** The member with a file's name, else the last member. */
  private static int memberOf(SetFiles files, Path path) {
    for (int i = 0; i < files.members().size(); i++) {
      if (files.members().get(i).path().equals(path)) {
        return i;
      }
    }
    return files.members().size() - 1;
  }

  /**
   * Copies marks, then the comments of the marks copied, through a map; runs on a worker.
   *
   * @param from the old document
   * @param to the new document
   * @param map the map, or null to keep line numbers
   * @return the outcome
   */
  @WorkerThread
  static Carried copy(LogDocument from, LogDocument to, LineMap map) {
    if (map == null) {
      return new Carried(null, DocumentOpener.copyMarksAndComments(from, to), 0);
    }
    List<LineMap.Entry> kept = new ArrayList<LineMap.Entry>();
    long dropped = 0;
    for (LineMap.Entry entry : map.members()) {
      if (entry.newBase() == LineMap.DROPPED) {
        dropped += count(from.marks(), entry.oldBase(), entry.oldBase() + entry.lines());
      } else {
        kept.add(entry);
      }
    }
    kept.sort(
        new Comparator<LineMap.Entry>() {
          @Override
          public int compare(LineMap.Entry a, LineMap.Entry b) {
            return Long.compare(a.newBase(), b.newBase());
          }
        });
    boolean complete = true;
    for (LineMap.Entry entry : kept) {
      complete &= shift(from.marks(), to.marks(), entry);
    }
    List<MarkComments.Comment> moved = new ArrayList<MarkComments.Comment>();
    for (MarkComments.Comment comment : from.markComments().list(0, MarkComments.MAX_COMMENTS)) {
      long line = map.map(comment.line());
      if (line != LineMap.DROPPED) {
        moved.add(new MarkComments.Comment(line, comment.text()));
      }
    }
    complete &= to.markComments().addAll(moved, to.marks());
    return new Carried(map, complete, dropped);
  }

  /**
   * Maps a line for the caret and the navigation history.
   *
   * @param map the map, or null
   * @param line an old line
   * @return the new line; 0 for a line on a dropped member
   */
  static long line(LineMap map, long line) {
    if (map == null) {
      return line;
    }
    long mapped = map.map(line);
    return mapped == LineMap.DROPPED ? 0 : mapped;
  }

  /** Adds the marks of one entry's old lines at its new base; false when the share refused. */
  private static boolean shift(MutableLineSet from, MutableLineSet to, LineMap.Entry entry) {
    long end = entry.oldBase() + entry.lines();
    long[] batch = new long[BATCH];
    long next = entry.oldBase();
    while (next < end) {
      int count = from.fill(next, batch);
      int used = 0;
      while (used < count && batch[used] < end) {
        batch[used] = batch[used] - entry.oldBase() + entry.newBase();
        used++;
      }
      if (used > 0 && !to.addSorted(batch, used)) {
        return false;
      }
      if (used < batch.length) {
        return true;
      }
      next = batch[used - 1] - entry.newBase() + entry.oldBase() + 1;
    }
    return true;
  }

  /** Counts the marks in {@code [from, end)}. */
  private static long count(MutableLineSet marks, long from, long end) {
    long[] batch = new long[BATCH];
    long counted = 0;
    long next = from;
    while (next < end) {
      int count = marks.fill(next, batch);
      int used = 0;
      while (used < count && batch[used] < end) {
        used++;
      }
      counted += used;
      if (used < batch.length) {
        return counted;
      }
      next = batch[used - 1] + 1;
    }
    return counted;
  }
}
