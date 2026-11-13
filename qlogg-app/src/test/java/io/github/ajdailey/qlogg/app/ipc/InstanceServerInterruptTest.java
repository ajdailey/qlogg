package io.github.ajdailey.qlogg.app.ipc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link InstanceServer}'s accept loop and held handoff end on an interrupt and leave the flag set
 * for the caller.
 */
class InstanceServerInterruptTest {
  @TempDir Path temp;

  private Workers workers;
  private InstanceServer server;

  @BeforeEach
  void setUp() throws Exception {
    workers =
        new Workers(
            1,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                throw new AssertionError(error);
              }
            });
    server = InstanceServer.claim(temp);
    assertNotNull(server);
  }

  @AfterEach
  void tearDown() {
    Thread.interrupted();
    server.close();
    assertTrue(workers.close(Workers.CLOSE_BOUND));
  }

  @Test
  void anInterruptedAcceptReturnsWithTheFlagSet() {
    Thread.currentThread().interrupt();
    server.accept(workers);
    assertTrue(Thread.interrupted());
  }

  @Test
  void anInterruptedHandRefusesTheFilesWithTheFlagSet() {
    List<FileRequest> files = List.of(new FileRequest(temp.resolve("a.log").toAbsolutePath(), 0));
    Thread.currentThread().interrupt();
    assertFalse(server.hand(files, false));
    assertTrue(Thread.interrupted());
    assertEquals(0, server.heldRequests());
  }
}
