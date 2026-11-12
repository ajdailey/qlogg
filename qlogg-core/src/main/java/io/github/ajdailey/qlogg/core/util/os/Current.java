package io.github.ajdailey.qlogg.core.util.os;

/** Holds this process's traits, chosen at first use. */
final class Current {
  static final OsTraits TRAITS = OsTraits.of(System.getProperty("os.name"));

  private Current() {}
}
