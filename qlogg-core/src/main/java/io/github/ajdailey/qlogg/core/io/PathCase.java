package io.github.ajdailey.qlogg.core.io;

import io.github.ajdailey.qlogg.core.util.os.OsTraits;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A platform's file name case rule ({@link OsTraits#fileNamesIgnoreCase}). Where names ignore case,
 * {@link #onDisk} gives a path its directory entries' spelling and {@link #same} matches two
 * spellings of one path; elsewhere both keep names exact. Immutable.
 */
public final class PathCase {
  private final boolean ignoreCase;

  private PathCase(boolean ignoreCase) {
    this.ignoreCase = ignoreCase;
  }

  /**
   * Returns a platform's rule.
   *
   * @param os the platform
   * @return the rule
   */
  public static PathCase of(OsTraits os) {
    return new PathCase(os.fileNamesIgnoreCase());
  }

  /**
   * Reports whether the file system compares names without regard to case.
   *
   * @return true on Windows and macOS
   */
  public boolean ignoreCase() {
    return ignoreCase;
  }

  /**
   * Returns a path in its on-disk spelling where names ignore case: each name is replaced by its
   * directory's entry equal to it apart from case, an exact match first. A name with no such entry
   * (a Windows short name, a missing file, a directory that cannot be listed) keeps the given
   * spelling. Symbolic links stay as given. Blocking: lists each directory of the path.
   *
   * @param path the path as given
   * @return the absolute, normalized path in the on-disk spelling; {@code path} unchanged where
   *     names keep their case
   */
  public Path onDisk(Path path) {
    if (!ignoreCase) {
      return path;
    }
    Path absolute = path.toAbsolutePath().normalize();
    Path spelled = absolute.getRoot();
    if (spelled == null) {
      return absolute;
    }
    for (Path name : absolute) {
      String entry = entry(spelled, name.toString());
      spelled = spelled.resolve(entry == null ? name.toString() : entry);
    }
    return spelled;
  }

  /**
   * Reports whether two path texts name one path: equal, or equal apart from case where names
   * ignore case.
   *
   * @param a a path's text
   * @param b another path's text
   * @return true when they match
   */
  public boolean same(String a, String b) {
    return a.equals(b) || (ignoreCase && apartFromCase(a, b));
  }

  /** Compares char by char apart from case, as {@code MemberNames} compares names. */
  private static boolean apartFromCase(String a, String b) {
    return a.length() == b.length() && a.regionMatches(true, 0, b, 0, b.length());
  }

  /** The entry of a directory named {@code name} apart from case, or null. */
  private static String entry(Path directory, String name) {
    String found = null;
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
      for (Path child : entries) {
        Path file = child.getFileName();
        String entry = file == null ? "" : file.toString();
        if (entry.equals(name)) {
          return entry;
        }
        if (found == null && apartFromCase(entry, name)) {
          found = entry;
        }
      }
    } catch (IOException | DirectoryIteratorException e) {
      return null;
    }
    return found;
  }
}
