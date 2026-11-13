package io.github.ajdailey.qlogg.app.ipc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** Each {@link InstanceServer}'s held lock protects its own lock file, whatever another does. */
class HeldLockTest {
  @TempDir Path temp;

  private static void assertRejected(final Path lock, final SafePaths.HeldLock held) {
    IOException refused =
        assertThrows(
            IOException.class,
            new Executable() {
              @Override
              public void execute() throws IOException {
                SafePaths.logPath(lock, held);
              }
            });
    assertEquals(UiText.INSTANCE_LOCK_REFUSED.text(), refused.getMessage());
  }

  @Test
  void closingOneServerLeavesAnotherServersLockProtected() throws Exception {
    Path firstDirectory = Files.createDirectory(temp.resolve("first"));
    Path secondDirectory = Files.createDirectory(temp.resolve("second"));
    InstanceServer first = InstanceServer.claim(firstDirectory);
    assertNotNull(first);
    try (InstanceServer second = InstanceServer.claim(secondDirectory)) {
      assertNotNull(second);
      first.close();
      assertRejected(secondDirectory.resolve(Endpoint.LOCK), second.heldLock());
    } finally {
      first.close();
    }
  }
}
