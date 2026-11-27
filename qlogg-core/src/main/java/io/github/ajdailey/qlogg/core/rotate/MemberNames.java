package io.github.ajdailey.qlogg.core.rotate;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * File names of one rotation series. A rotation token is {@code \d+([._-]\d+)*}; a member is the
 * base name, the base name plus a separator ({@code .}, {@code _} or {@code -}) and a token, or the
 * base's stem plus a separator, a token and the base's extension. Any of these may end in a
 * compression extension. Names compare with or without regard to case, as the file system does
 * ({@code OsTraits.fileNamesIgnoreCase}).
 */
public final class MemberNames {
  private static final String TOKEN = "\\d+(?:[._-]\\d+)*";
  private static final Pattern TOKEN_ONLY = Pattern.compile(TOKEN);
  private static final Pattern SUFFIX = Pattern.compile("(.+?)[._-](" + TOKEN + ")");
  private static final Pattern BEFORE_EXTENSION =
      Pattern.compile("(.+?)[._-](" + TOKEN + ")(\\.[A-Za-z][^.]*)");
  private static final Pattern EXTENSION = Pattern.compile("(.+)(\\.[A-Za-z][^.]*)");
  private static final String[] COMPRESSED = {".gz", ".bz2", ".xz", ".zst", ".zip", ".Z"};

  /**
   * A name that belongs to the series.
   *
   * @param token the rotation token, or null for the base name
   * @param compressed true when the name ends in a compression extension
   */
  public record Match(String token, boolean compressed) {}

  private final String base;
  private final String stem;
  private final String extension;
  private final boolean ignoreCase;

  private MemberNames(String base, boolean ignoreCase) {
    this.base = base;
    this.ignoreCase = ignoreCase;
    Matcher split = EXTENSION.matcher(base);
    if (split.matches()) {
      this.stem = split.group(1);
      this.extension = split.group(2);
    } else {
      this.stem = null;
      this.extension = null;
    }
  }

  /**
   * Creates the matcher for the series a file name belongs to.
   *
   * @param name any member's file name, the base included
   * @param ignoreCase true where the file system compares names without regard to case
   * @return the matcher for {@link #baseOf} the name
   */
  public static MemberNames of(String name, boolean ignoreCase) {
    return new MemberNames(baseOf(name, ignoreCase), ignoreCase);
  }

  /**
   * Returns a name with its compression extension and rotation token removed: {@code app.log.1},
   * {@code app.1.log} and {@code app-2026-11-25.log} all give {@code app.log}. A token at the end
   * is removed before one in front of the extension.
   *
   * @param name a file name
   * @param ignoreCase true to remove a compression extension in any case
   * @return the base name; the name itself when it has no token
   */
  public static String baseOf(String name, boolean ignoreCase) {
    String plain = uncompressed(name, ignoreCase);
    Matcher suffix = SUFFIX.matcher(plain);
    if (suffix.matches()) {
      return suffix.group(1);
    }
    Matcher before = BEFORE_EXTENSION.matcher(plain);
    if (before.matches()) {
      return before.group(1) + before.group(3);
    }
    return plain;
  }

  /**
   * Returns the base name.
   *
   * @return the name with no token
   */
  public String base() {
    return base;
  }

  /**
   * Matches a file name against the series.
   *
   * @param name a file name in the base's directory
   * @return the match, or null when the name is not a member
   */
  public Match match(String name) {
    String plain = uncompressed(name, ignoreCase);
    boolean compressed = plain.length() != name.length();
    if (plain.length() == base.length()
        && plain.regionMatches(ignoreCase, 0, base, 0, base.length())) {
      return new Match(null, compressed);
    }
    String token = tokenAfter(plain, base, "");
    if (token == null && extension != null) {
      token = tokenAfter(plain, stem, extension);
    }
    return token == null ? null : new Match(token, compressed);
  }

  private String tokenAfter(String name, String prefix, String suffix) {
    int from = prefix.length() + 1;
    int to = name.length() - suffix.length();
    if (to <= from
        || !name.regionMatches(ignoreCase, 0, prefix, 0, prefix.length())
        || !name.regionMatches(ignoreCase, to, suffix, 0, suffix.length())
        || "._-".indexOf(name.charAt(prefix.length())) < 0) {
      return null;
    }
    String token = name.substring(from, to);
    return TOKEN_ONLY.matcher(token).matches() ? token : null;
  }

  private static String uncompressed(String name, boolean ignoreCase) {
    for (String extension : COMPRESSED) {
      int at = name.length() - extension.length();
      if (at > 0 && name.regionMatches(ignoreCase, at, extension, 0, extension.length())) {
        return name.substring(0, name.length() - extension.length());
      }
    }
    return name;
  }
}
