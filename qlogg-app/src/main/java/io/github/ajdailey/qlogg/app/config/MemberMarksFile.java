package io.github.ajdailey.qlogg.app.config;

import io.github.ajdailey.qlogg.core.model.MarkComments;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * A rotated tab's marks and comments in {@code qlogg_session.conf}, per member: {@code .rmarks.K}
 * is the member's head digest in hex, a space and its ranges in member lines, and {@code
 * .rcomment.K} the digest, the member line and the text, each numbered from 0 without gaps.
 */
final class MemberMarksFile {
  private static final String MEMBER_MARKS = "rmarks.";
  private static final String MEMBER_COMMENT = "rcomment.";

  private MemberMarksFile() {}

  /** Writes a rotated tab's member marks and comments; returns the comments written. */
  static int write(Properties p, String key, List<Session.MemberMarks> members, int maxComments) {
    int k = 0;
    int c = 0;
    for (Session.MemberMarks member : members) {
      String digest = Long.toHexString(member.digest());
      if (member.marks().length > 0) {
        p.setProperty(
            key + MEMBER_MARKS + k++, digest + " " + SessionStore.formatMarks(member.marks()));
      }
      for (int i = 0; i < member.comments().size() && c < maxComments; i++) {
        MarkComments.Comment comment = member.comments().get(i);
        p.setProperty(
            key + MEMBER_COMMENT + c++, digest + " " + comment.line() + " " + comment.text());
      }
    }
    return c;
  }

  /**
   * Reads a rotated tab's member marks and comments; an entry without a digest is skipped, and a
   * comment whose digest has no marks is dropped.
   */
  static List<Session.MemberMarks> read(Properties p, String key, int maxRanges, int maxComments) {
    Map<Long, long[]> marks = new LinkedHashMap<Long, long[]>();
    int ranges = 0;
    for (int k = 0; ; k++) {
      String value = p.getProperty(key + MEMBER_MARKS + k);
      if (value == null) {
        break;
      }
      int space = value.indexOf(' ');
      Long digest = space <= 0 ? null : digest(value.substring(0, space));
      if (digest != null && !marks.containsKey(digest)) {
        long[] parsed = SessionStore.parseMarks(value.substring(space + 1), maxRanges - ranges);
        ranges += parsed.length / 2;
        marks.put(digest, parsed);
      }
    }
    Map<Long, List<MarkComments.Comment>> comments =
        new HashMap<Long, List<MarkComments.Comment>>();
    int read = 0;
    for (int k = 0; read < maxComments; k++) {
      String value = p.getProperty(key + MEMBER_COMMENT + k);
      if (value == null) {
        break;
      }
      int space = value.indexOf(' ');
      Long digest = space <= 0 ? null : digest(value.substring(0, space));
      if (digest == null || !marks.containsKey(digest)) {
        continue;
      }
      MarkComments.Comment parsed = SessionStore.comment(value.substring(space + 1));
      if (parsed != null) {
        List<MarkComments.Comment> list = comments.get(digest);
        if (list == null) {
          list = new ArrayList<MarkComments.Comment>();
          comments.put(digest, list);
        }
        list.add(parsed);
        read++;
      }
    }
    List<Session.MemberMarks> members = new ArrayList<Session.MemberMarks>();
    for (Map.Entry<Long, long[]> entry : marks.entrySet()) {
      List<MarkComments.Comment> list = comments.get(entry.getKey());
      members.add(
          new Session.MemberMarks(
              entry.getKey(),
              entry.getValue(),
              list == null ? List.<MarkComments.Comment>of() : list));
    }
    return members;
  }

  /** Reads a hex digest; null when malformed. */
  private static Long digest(String text) {
    try {
      return Long.parseUnsignedLong(text, 16);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
