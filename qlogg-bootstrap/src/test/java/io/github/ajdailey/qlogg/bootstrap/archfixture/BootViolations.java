package io.github.ajdailey.qlogg.bootstrap.archfixture;

/** Classes that break a bootstrap architecture rule, so each rule is shown to fail. */
final class BootViolations {
  private BootViolations() {}

  /** Reads the OS name outside {@code Target}. */
  static final class ReadsOsName {
    String os() {
      return System.getProperty("os.name");
    }
  }
}
