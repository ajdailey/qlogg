package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.StatusText;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.rotate.LeftOut;
import io.github.ajdailey.qlogg.core.rotate.LineMap;
import io.github.ajdailey.qlogg.core.rotate.MemberNames;
import io.github.ajdailey.qlogg.core.rotate.RotatedIndex;
import io.github.ajdailey.qlogg.core.rotate.RotatedSet;
import io.github.ajdailey.qlogg.core.rotate.SetFiles;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The texts and line lookups of a tab that shows a rotated set: its title, tooltip, status bar
 * member, left-out notices and the member boundaries. Every method returns the single-file answer
 * for a document that is not a set.
 */
final class RotatedTabs {
  private RotatedTabs() {}

  /**
   * Returns the base path of the series a file belongs to.
   *
   * @param path any member's path
   * @param ignoreCase true where the file system compares names without regard to case
   * @return the path with its rotation token and compression extension removed
   */
  static Path base(Path path, boolean ignoreCase) {
    Path name = path.getFileName();
    if (name == null) {
      return path;
    }
    return path.resolveSibling(MemberNames.baseOf(name.toString(), ignoreCase));
  }

  /**
   * Returns a tab's title.
   *
   * @param document the document
   * @return the name, followed by {@code +N} for a set with N files before the newest
   */
  static String title(LogDocument document) {
    SetFiles files = document.files();
    if (files == null || files.members().size() < 2) {
      return document.name();
    }
    return UiText.TAB_SET_TITLE.format(document.name(), files.members().size() - 1);
  }

  /**
   * Lists a set's files for the tab tooltip: each member in order with its size and modification
   * time, then the files left out with the reason.
   *
   * @param document the document
   * @param zone the zone the times show in
   * @return one line per file, or empty for one file
   */
  static String tooltip(LogDocument document, ZoneId zone) {
    SetFiles files = document.files();
    if (files == null) {
      return "";
    }
    List<String> lines = new ArrayList<String>();
    for (SetFiles.Loaded member : files.members()) {
      lines.add(
          UiText.TAB_SET_MEMBER.format(
              name(member.path()),
              StatusText.size(member.size()),
              StatusText.modified(member.modifiedMillis(), zone)));
    }
    for (LeftOut file : files.leftOut()) {
      UiText reason =
          switch (file.reason()) {
            case COMPRESSED -> UiText.TAB_SET_COMPRESSED;
            case ENCODING -> UiText.TAB_SET_ENCODING;
            case OVER_CAP -> UiText.TAB_SET_OVER_CAP;
          };
      lines.add(reason.format(name(file.path())));
    }
    return String.join("\n", lines);
  }

  /**
   * Returns the status bar note of a set's compressed files.
   *
   * @param document the document
   * @return the note, or empty
   */
  static String note(LogDocument document) {
    SetFiles files = document.files();
    int compressed = files == null ? 0 : files.leftOut(LeftOut.Reason.COMPRESSED);
    return compressed == 0 ? "" : UiText.STATUS_SET_COMPRESSED.format(compressed);
  }

  /**
   * Lists the notices of a newly opened set's left-out files.
   *
   * @param document the document
   * @return one notice per reason with files, possibly none
   */
  static List<String> leftOutNotices(LogDocument document) {
    List<String> notices = new ArrayList<String>();
    SetFiles files = document.files();
    if (files == null) {
      return notices;
    }
    int over = files.leftOut(LeftOut.Reason.OVER_CAP);
    if (over > 0) {
      notices.add(UiText.NOTICE_SET_OVER_CAP.format(document.name(), over, RotatedSet.MAX_MEMBERS));
    }
    int encoding = files.leftOut(LeftOut.Reason.ENCODING);
    if (encoding > 0) {
      notices.add(UiText.NOTICE_SET_ENCODING.format(document.name(), encoding));
    }
    int compressed = files.leftOut(LeftOut.Reason.COMPRESSED);
    if (compressed > 0) {
      notices.add(UiText.NOTICE_SET_COMPRESSED.format(document.name(), compressed));
    }
    return notices;
  }

  /**
   * Returns the notice of a roll-over that renumbered a set's lines. It names the set by its base
   * name: the reloaded set's file count changes as files join after the reload.
   *
   * @param base the set's base name
   * @param map the roll-over's map, or null
   * @param droppedMarks marks left out with their files
   * @return the notice
   */
  static String rolledNotice(String base, LineMap map, long droppedMarks) {
    List<String> left = new ArrayList<String>();
    if (map != null) {
      for (Path path : map.dropped()) {
        left.add(name(path));
      }
    }
    String notice =
        left.isEmpty()
            ? UiText.NOTICE_SET_ROLLED.format(base)
            : UiText.NOTICE_SET_ROLLED_LEFT.format(base, String.join(", ", left));
    return droppedMarks == 0
        ? notice
        : notice + StatusText.GAP + UiText.NOTICE_SET_MARKS_DROPPED.format(droppedMarks);
  }

  /**
   * Names the member of a line and the line's number in it, for the status bar.
   *
   * @param document the document
   * @param line a 0-based line
   * @return for example {@code app.log.2:1,204}, or empty for one file or a line past the index
   */
  static String member(LogDocument document, long line) {
    LineIndex index = document.index();
    SetFiles files = document.files();
    if (!(index instanceof RotatedIndex rotated)
        || files == null
        || line < 0
        || line >= rotated.lineCount()) {
      return "";
    }
    int member = rotated.memberOf(line);
    if (member >= files.members().size()) {
      return "";
    }
    return UiText.STATUS_SET_MEMBER.format(
        name(files.members().get(member).path()), rotated.memberLine(line) + 1);
  }

  /**
   * Reports whether a line starts a member after the first, for the boundary rule.
   *
   * @param document the document
   * @param line a 0-based line
   * @return false for one file
   */
  static boolean startsMember(LogDocument document, long line) {
    LineIndex index = document.index();
    return index instanceof RotatedIndex rotated
        && line > 0
        && line < rotated.lineCount()
        && rotated.memberLine(line) == 0;
  }

  /**
   * Returns the line of a set that shows a member's line.
   *
   * @param document the document
   * @param file a member's path, as opened or in the set's real directory
   * @param memberLine a 0-based line in that member
   * @return the 0-based line in the set, or -1 when the file is not an indexed member of it
   */
  static long setLine(LogDocument document, Path file, long memberLine) {
    LineIndex index = document.index();
    int member = memberOf(document, file);
    if (!(index instanceof RotatedIndex rotated) || member < 0) {
      return -1;
    }
    for (long start : rotated.memberStarts()) {
      if (rotated.memberOf(start) == member) {
        long line = start + memberLine;
        return line < rotated.lineCount() && rotated.memberOf(line) == member ? line : -1;
      }
    }
    return -1;
  }

  /**
   * Returns the member of a set that a file is.
   *
   * @param document the document
   * @param file a path, as opened or in the set's real directory
   * @return the member number, or -1 when the file is not a loaded member or the document no set
   */
  static int memberOf(LogDocument document, Path file) {
    SetFiles files = document.files();
    if (files == null) {
      return -1;
    }
    for (int i = 0; i < files.members().size(); i++) {
      Path path = files.members().get(i).path();
      Path name = path.getFileName();
      if (path.equals(file)
          || (name != null && document.realPath().resolveSibling(name).equals(file))) {
        return i;
      }
    }
    return -1;
  }

  private static String name(Path path) {
    Path name = path.getFileName();
    return name == null ? path.toString() : name.toString();
  }
}
