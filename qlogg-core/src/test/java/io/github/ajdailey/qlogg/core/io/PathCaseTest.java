package io.github.ajdailey.qlogg.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.core.util.os.OsTraits;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PathCase}: the on-disk spelling of a path typed in another case, and path matching. The
 * Mac rule makes the on-disk cases run on a case-sensitive file system.
 */
class FileNamesTest {
  private static final PathCase EXACT = PathCase.of(OsTraits.of("Linux"));
  private static final PathCase IGNORING = PathCase.of(OsTraits.of("Mac OS X"));

  @TempDir Path temp;

  @Test
  void namesThatKeepTheirCaseLeaveThePathAsGiven() {
    Path typed = temp.resolve("LOGS").resolve("APP.LOG.1");
    assertSame(typed, EXACT.onDisk(typed));
  }

  @Test
  void eachNameTakesItsDirectoryEntrysSpelling() throws Exception {
    Path file = Files.createDirectories(temp.resolve("Logs")).resolve("app.log.1");
    Files.writeString(file, "mock\n");
    assertEquals(file, IGNORING.onDisk(temp.resolve("LOGS").resolve("APP.LOG.1")));
  }

  @Test
  void aNameWithNoEntryKeepsItsSpelling() throws Exception {
    Path logs = Files.createDirectories(temp.resolve("Logs"));
    assertEquals(
        logs.resolve("Missing").resolve("APP.LOG"),
        IGNORING.onDisk(temp.resolve("LOGS").resolve("Missing").resolve("APP.LOG")));
  }

  /** Two entries that differ only in case exist only where names keep their case. */
  @Test
  void anExactEntryWinsOverOneInAnotherCase() throws Exception {
    Path lower = Files.writeString(temp.resolve("app.log"), "lower\n");
    Path upper = temp.resolve("APP.LOG");
    assumeTrue(!Files.exists(upper), "names ignore case here");
    Files.writeString(upper, "upper\n");
    assertEquals(lower, IGNORING.onDisk(lower));
    assertEquals(upper, IGNORING.onDisk(upper));
  }

  @Test
  void pathsMatchApartFromCaseOnlyWhereNamesIgnoreCase() {
    assertTrue(EXACT.same("/mock/app.log", "/mock/app.log"));
    assertFalse(EXACT.same("/mock/app.log", "/MOCK/APP.LOG"));
    assertTrue(IGNORING.same("/mock/app.log", "/MOCK/APP.LOG"));
    assertFalse(IGNORING.same("/mock/app.log", "/mock/app.log.1"));
  }
}
