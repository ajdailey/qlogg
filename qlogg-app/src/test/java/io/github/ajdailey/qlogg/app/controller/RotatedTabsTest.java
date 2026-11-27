package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link RotatedTabs#onDisk}: the on-disk spelling of a path typed in another case. */
class RotatedTabsTest {
  @TempDir Path temp;

  @Test
  void namesThatKeepTheirCaseLeaveThePathAsGiven() {
    Path typed = temp.resolve("LOGS").resolve("APP.LOG.1");
    assertSame(typed, RotatedTabs.onDisk(typed, false));
  }

  @Test
  void eachNameTakesItsDirectoryEntrysSpelling() throws Exception {
    Path file = Files.createDirectories(temp.resolve("Logs")).resolve("app.log.1");
    Files.writeString(file, "mock\n");
    assertEquals(file, RotatedTabs.onDisk(temp.resolve("LOGS").resolve("APP.LOG.1"), true));
  }

  @Test
  void aNameWithNoEntryKeepsItsSpelling() throws Exception {
    Path logs = Files.createDirectories(temp.resolve("Logs"));
    assertEquals(
        logs.resolve("Missing").resolve("APP.LOG"),
        RotatedTabs.onDisk(temp.resolve("LOGS").resolve("Missing").resolve("APP.LOG"), true));
  }

  /** Two entries that differ only in case exist only where names keep their case. */
  @Test
  void anExactEntryWinsOverOneInAnotherCase() throws Exception {
    Path lower = Files.writeString(temp.resolve("app.log"), "lower\n");
    Path upper = temp.resolve("APP.LOG");
    assumeTrue(!Files.exists(upper), "names ignore case here");
    Files.writeString(upper, "upper\n");
    assertEquals(lower, RotatedTabs.onDisk(lower, true));
    assertEquals(upper, RotatedTabs.onDisk(upper, true));
  }
}
