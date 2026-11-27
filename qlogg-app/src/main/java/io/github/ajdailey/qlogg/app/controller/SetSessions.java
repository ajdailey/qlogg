package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.config.Session;
import io.github.ajdailey.qlogg.app.config.SessionStore;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.MarkComments;
import io.github.ajdailey.qlogg.core.rotate.RotatedIndex;
import io.github.ajdailey.qlogg.core.rotate.SetFiles;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.text.LogText;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts a rotated tab's marks and comments between its running line numbers and the per-member
 * lines a session keeps, keyed by each member's head digest, so they survive a roll-over between
 * sessions. Runs on workers.
 */
final class SetSessions {
  private static final Logger LOG = LoggerFactory.getLogger(SetSessions.class);
  private static final int BATCH = 4096;

  /**
   * A tab's member marks for the session.
   *
   * @param members one entry per member with marks, oldest first
   * @param ranges ranges saved
   * @param comments comments saved
   * @param droppedComments comments left out at the cap
   */
  record Saved(List<Session.MemberMarks> members, int ranges, int comments, long droppedComments) {}

  /**
   * The outcome of a restore.
   *
   * @param complete false when the {@code RESULTS} share refused part of the marks or comments
   * @param droppedMarks marked lines whose member is not in the set
   */
  record Restored(boolean complete, long droppedMarks) {}

  private SetSessions() {}

  /**
   * Splits a set's marks and comments by member. Call inside the document's {@code enter}.
   *
   * @param document a rotated set
   * @param maxRanges most ranges kept
   * @param maxComments most comments kept
   * @return the member marks
   */
  @WorkerThread
  static Saved save(LogDocument document, int maxRanges, int maxComments) {
    LineIndex index = document.index();
    SetFiles files = document.files();
    List<Session.MemberMarks> members = new ArrayList<Session.MemberMarks>();
    int ranges = 0;
    int comments = 0;
    if (!(index instanceof RotatedIndex rotated) || files == null) {
      return new Saved(members, 0, 0, document.markComments().size());
    }
    long[] starts = rotated.memberStarts();
    for (int i = 0; i < starts.length && ranges < maxRanges; i++) {
      int member = rotated.memberOf(starts[i]);
      if (member >= files.members().size()) {
        break;
      }
      long end = i + 1 < starts.length ? starts[i + 1] : rotated.lineCount();
      long[] marks = ranges(document.marks(), starts[i], end, maxRanges - ranges);
      if (marks.length == 0) {
        continue;
      }
      ranges += marks.length / 2;
      List<MarkComments.Comment> moved = new ArrayList<MarkComments.Comment>();
      for (MarkComments.Comment comment :
          document.markComments().list(starts[i], maxComments - comments)) {
        if (comment.line() >= end) {
          break;
        }
        moved.add(new MarkComments.Comment(comment.line() - starts[i], comment.text()));
      }
      comments += moved.size();
      members.add(new Session.MemberMarks(files.members().get(member).headDigest(), marks, moved));
    }
    return new Saved(members, ranges, comments, document.markComments().size() - comments);
  }

  /**
   * Adds a session's member marks and comments to a newly opened set, each at its member's first
   * line; marks of a digest that no member has are counted and left out. Blocking: counts the lines
   * of the members before the last. A failed count logs a WARN and adds none.
   *
   * @param document the set, not yet indexed
   * @param members the session's member marks
   * @param closing true ends the count
   * @return the outcome
   */
  @WorkerThread
  static Restored restore(
      LogDocument document, List<Session.MemberMarks> members, BooleanSupplier closing) {
    SetFiles files = document.files();
    if (files == null || members.isEmpty()) {
      return new Restored(true, 0);
    }
    long[] bases;
    try {
      bases = document.memberBases(closing);
    } catch (IOException e) {
      LOG.warn(
          "Marks of {} not restored: {}",
          LogText.oneLine(document.path()),
          LogText.oneLine(e.getMessage()));
      return new Restored(false, 0);
    }
    Map<Long, Integer> byDigest = new HashMap<Long, Integer>();
    for (int i = files.members().size() - 1; i >= 0; i--) {
      byDigest.put(files.members().get(i).headDigest(), i);
    }
    boolean complete = true;
    long dropped = 0;
    List<MarkComments.Comment> comments = new ArrayList<MarkComments.Comment>();
    for (Session.MemberMarks member : members) {
      Integer found = byDigest.get(member.digest());
      if (found == null || found >= bases.length) {
        dropped += lines(member.marks());
        continue;
      }
      long base = bases[found];
      long limit = found + 1 < bases.length ? bases[found + 1] - base : Long.MAX_VALUE;
      long[] shifted = shift(member.marks(), base, limit);
      dropped += lines(member.marks()) - lines(shifted);
      complete &= SessionStore.addRanges(shifted, document.marks());
      for (MarkComments.Comment comment : member.comments()) {
        if (comment.line() < limit) {
          comments.add(new MarkComments.Comment(comment.line() + base, comment.text()));
        }
      }
    }
    complete &= document.markComments().addAll(comments, document.marks());
    return new Restored(complete, dropped);
  }

  /** Ranges shifted by {@code base}, cut at member line {@code limit}. */
  private static long[] shift(long[] ranges, long base, long limit) {
    long[] out = new long[ranges.length];
    int n = 0;
    for (int i = 0; i + 1 < ranges.length && ranges[i] < limit; i += 2) {
      out[n++] = ranges[i] + base;
      out[n++] = Math.min(ranges[i + 1], limit - 1) + base;
    }
    return Arrays.copyOf(out, n);
  }

  private static long lines(long[] ranges) {
    long count = 0;
    for (int i = 0; i + 1 < ranges.length; i += 2) {
      count += ranges[i + 1] - ranges[i] + 1;
    }
    return count;
  }

  /** The marks in {@code [from, end)} as ranges relative to {@code from}. */
  private static long[] ranges(MutableLineSet marks, long from, long end, int maxRanges) {
    long[] batch = new long[BATCH];
    long[] out = new long[16];
    int n = 0;
    long next = from;
    while (next < end) {
      int count = marks.fill(next, batch);
      int used = 0;
      while (used < count && batch[used] < end) {
        long line = batch[used++] - from;
        if (n > 0 && out[n - 1] == line - 1) {
          out[n - 1] = line;
        } else if (n / 2 < maxRanges) {
          if (n + 2 > out.length) {
            out = Arrays.copyOf(out, out.length * 2);
          }
          out[n++] = line;
          out[n++] = line;
        } else {
          return Arrays.copyOf(out, n);
        }
      }
      if (used < batch.length) {
        break;
      }
      next = batch[used - 1] + 1;
    }
    return Arrays.copyOf(out, n);
  }
}
