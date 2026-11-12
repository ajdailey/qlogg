package io.github.ajdailey.qlogg.core.util.os;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Paths read from environment variables and system properties. */
public final class EnvPaths {
  private EnvPaths() {}

  /**
   * Parses a value as an absolute path.
   *
   * @param value the variable's value, or null
   * @return the normalized path, or null when the value is null, empty, holds NUL, is not a path or
   *     is relative
   */
  public static Path absolute(String value) {
    if (value == null || value.isEmpty() || value.indexOf('\0') >= 0) {
      return null;
    }
    try {
      Path path = Path.of(value);
      return path.isAbsolute() ? path.normalize() : null;
    } catch (InvalidPathException e) {
      return null;
    }
  }
}
