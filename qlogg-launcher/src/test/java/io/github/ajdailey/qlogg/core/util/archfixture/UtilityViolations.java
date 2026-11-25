package io.github.ajdailey.qlogg.core.util.archfixture;

import io.github.ajdailey.qlogg.core.folder.NamePattern;
import io.github.ajdailey.qlogg.core.format.JsonStrings;

/** A utility class that depends on two other core packages; never instantiated. */
final class UtilityViolations {
  private UtilityViolations() {}

  static boolean matches(String name) {
    return NamePattern.of(JsonStrings.unescape(name)).toString().isEmpty();
  }
}
