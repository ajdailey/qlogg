package io.github.ajdailey.qlogg.core.testkit;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/** Atomic rename over a name that a running check may have open. */
public final class Renames {
  private static final Duration BOUND = Duration.ofSeconds(5);
  private static final long INTERVAL_MILLIS = 10;

  private Renames() {}

  /**
   * Renames {@code source} over {@code target} atomically, retrying up to five seconds on {@link
   * AccessDeniedException}: Windows refuses a rename over a name that is open at that instant.
   *
   * @param source the file or link to move
   * @param target the name to replace
   * @throws IOException on another failure, or the last refusal once the bound passes
   * @throws InterruptedException when interrupted between attempts
   */
  public static void replace(Path source, Path target) throws IOException, InterruptedException {
    long deadline = System.nanoTime() + BOUND.toNanos();
    while (true) {
      try {
        Files.move(
            source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return;
      } catch (AccessDeniedException e) {
        if (System.nanoTime() - deadline >= 0) {
          throw e;
        }
        Thread.sleep(INTERVAL_MILLIS);
      }
    }
  }
}
