package io.github.ajdailey.qlogg.core.folder;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * File name globs, {@code ;}-separated, matched against one name element in the default file
 * system's glob syntax: {@code *.log*} takes {@code app.log}, {@code app.log.1} and {@code
 * app.log.2.gz}. Case follows the file system (case-insensitive on Windows). Immutable.
 */
public final class NamePattern {
  /** The pattern a folder search starts with: a log and its rotated copies. */
  public static final String DEFAULT = "*.log*";

  private final String text;
  private final List<PathMatcher> globs;

  private NamePattern(String text, List<PathMatcher> globs) {
    this.text = text;
    this.globs = globs;
  }

  /**
   * Parses globs.
   *
   * @param text {@code ;}-separated globs; blank parts are ignored, and no glob at all takes every
   *     name
   * @return the pattern
   * @throws IllegalArgumentException for a glob that does not parse
   */
  public static NamePattern of(String text) {
    List<PathMatcher> globs = new ArrayList<PathMatcher>();
    for (String part : text.split(";", -1)) {
      String glob = part.strip();
      if (!glob.isEmpty()) {
        globs.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
      }
    }
    return new NamePattern(text, globs);
  }

  /**
   * Reports whether a name matches.
   *
   * @param name one name element, not a path
   * @return true when a glob matches it, or there is none
   */
  public boolean matches(Path name) {
    if (globs.isEmpty()) {
      return true;
    }
    for (PathMatcher glob : globs) {
      if (glob.matches(name)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reports whether a glob matches a name; the exclude test of a folder search.
   *
   * @param name one name element, not a path
   * @return true when a glob matches it; false when there is none
   */
  public boolean excludes(Path name) {
    return !globs.isEmpty() && matches(name);
  }

  @Override
  public String toString() {
    return text;
  }
}
