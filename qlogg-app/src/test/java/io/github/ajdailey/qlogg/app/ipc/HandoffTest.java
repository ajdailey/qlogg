package io.github.ajdailey.qlogg.app.ipc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.testkit.Poll;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** The single-instance handoff over loopback, client and server in this JVM. */
class HandoffTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final List<List<FileRequest>> received = new CopyOnWriteArrayList<List<FileRequest>>();
  private final List<Boolean> follows = new CopyOnWriteArrayList<Boolean>();
  private Workers workers;
  private InstanceServer server;

  @BeforeEach
  void setUp() {
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
    assertTrue(workers.close(Workers.CLOSE_BOUND));
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private void startPrimary() throws IOException {
    startPrimary(InstanceServer.claim(temp));
  }

  private void startPrimary(InstanceServer claimed) throws IOException {
    server = claimed;
    assertNotNull(server);
    assertTrue(
        server.start(
            workers,
            new InstanceServer.Receiver() {
              @Override
              public boolean deliver(List<FileRequest> files, boolean follow) {
                received.add(files);
                follows.add(follow);
                return true;
              }
            }));
  }

  private List<FileRequest> files(String... names) {
    List<FileRequest> files = new ArrayList<FileRequest>();
    for (int i = 0; i < names.length; i++) {
      files.add(new FileRequest(temp.resolve(names[i]).toAbsolutePath(), i * 10));
    }
    return files;
  }

  @Test
  void filesAndFollowReachThePrimary() throws Exception {
    startPrimary();
    List<FileRequest> sent = files("a.log", "b \u00e9 c.log");
    InstanceClient.handOff(temp, sent, true);
    assertEquals(List.of(sent), received);
    assertEquals(List.of(Boolean.TRUE), follows);
  }

  @Test
  void aLaunchWithoutFilesStillHandsOff() throws Exception {
    startPrimary();
    InstanceClient.handOff(temp, List.of(), false);
    assertEquals(List.of(List.of()), received);
  }

  @Test
  void aWrongTokenIsRefusedAndNothingIsOpened() throws Exception {
    startPrimary();
    Endpoint real = Endpoint.read(temp);
    new Endpoint(real.port(), Protocol.secret()).write(temp);
    assertThrows(
        IOException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            InstanceClient.handOff(temp, files("a.log"), false);
          }
        });
    assertTrue(received.isEmpty());
  }

  @Test
  void aPeerWithoutTheTokenGetsNoReplyAndOpensNothing() throws Exception {
    startPrimary();
    Endpoint endpoint = Endpoint.read(temp);
    long deadline = System.nanoTime() + InstanceServer.EXCHANGE_BOUND.toNanos() * 2;
    try (LineChannel line = LineChannel.connect(endpoint.port(), Duration.ofSeconds(1), deadline)) {
      line.writeLine(Protocol.HELLO + Protocol.encode(Protocol.secret()));
      assertTrue(line.readLine().startsWith(Protocol.PROOF));
      line.writeLine(Protocol.AUTH + Protocol.encode(Protocol.secret()));
      assertThrows(
          IOException.class,
          new Executable() {
            @Override
            public void execute() throws Throwable {
              line.readLine();
            }
          });
    }
    assertTrue(received.isEmpty());
  }

  @Test
  void aListenerWithoutTheTokenReceivesNoFile() throws Exception {
    try (ServerSocketChannel fake = ServerSocketChannel.open()) {
      fake.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      int port = ((InetSocketAddress) fake.getLocalAddress()).getPort();
      new Endpoint(port, Protocol.secret()).write(temp);
      final List<String> seen = new CopyOnWriteArrayList<String>();
      workers.start(
          Workers.Kind.IPC,
          new Runnable() {
            @Override
            public void run() {
              try (LineChannel line =
                  LineChannel.accepted(fake.accept(), System.nanoTime() + BOUND.toNanos())) {
                seen.add(line.readLine());
                line.writeLine(
                    Protocol.PROOF
                        + Protocol.encode(Protocol.secret())
                        + " "
                        + Protocol.encode(new byte[32]));
                while (true) {
                  seen.add(line.readLine());
                }
              } catch (IOException e) {
                seen.add("closed");
              }
            }
          });
      assertThrows(
          IOException.class,
          new Executable() {
            @Override
            public void execute() throws Throwable {
              InstanceClient.handOff(temp, files("secret.log"), false);
            }
          });
      assertTrue(
          Poll.until(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return seen.contains("closed");
                }
              },
              BOUND));
      assertEquals(2, seen.size(), seen.toString());
      assertTrue(seen.get(0).startsWith(Protocol.HELLO));
    }
  }

  @Test
  void aStaleEndpointWithoutTheLockIsReplaced() throws Exception {
    int deadPort;
    try (ServerSocketChannel gone = ServerSocketChannel.open()) {
      gone.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      deadPort = ((InetSocketAddress) gone.getLocalAddress()).getPort();
    }
    new Endpoint(deadPort, Protocol.secret()).write(temp);
    startPrimary();
    assertTrue(Endpoint.read(temp).port() != deadPort);
    InstanceClient.handOff(temp, files("a.log"), false);
    assertEquals(1, received.size());
  }

  @Test
  void aStaleEndpointBehindALiveLockFailsSoTheLaunchOpensItsOwnWindow() throws Exception {
    startPrimary();
    int deadPort;
    try (ServerSocketChannel gone = ServerSocketChannel.open()) {
      gone.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      deadPort = ((InetSocketAddress) gone.getLocalAddress()).getPort();
    }
    new Endpoint(deadPort, Protocol.secret()).write(temp);
    assertNull(InstanceServer.claim(temp));
    assertThrows(
        IOException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            InstanceClient.handOff(temp, files("a.log"), false);
          }
        });
    assertTrue(received.isEmpty());
  }

  @Test
  void aMissingEndpointIsWaitedForThenFails() throws Exception {
    startPrimary();
    Endpoint.delete(temp);
    long start = System.nanoTime();
    assertThrows(
        IOException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            InstanceClient.handOff(temp, files("a.log"), false);
          }
        });
    long waited = System.nanoTime() - start;
    assertTrue(waited >= InstanceClient.ENDPOINT_WAIT.toNanos(), Long.toString(waited));
    assertTrue(waited < BOUND.toNanos(), Long.toString(waited));
  }

  @Test
  void concurrentLaunchesAllReachThePrimary() throws Exception {
    startPrimary();
    final int launches = InstanceServer.MAX_CONNECTIONS;
    final List<Throwable> errors = new CopyOnWriteArrayList<Throwable>();
    for (int i = 0; i < launches; i++) {
      final List<FileRequest> sent = files("launch" + i + ".log");
      workers.start(
          Workers.Kind.IPC,
          new Runnable() {
            @Override
            public void run() {
              try {
                InstanceClient.handOff(temp, sent, false);
              } catch (IOException e) {
                errors.add(e);
              }
            }
          });
    }
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return received.size() + errors.size() == launches;
              }
            },
            BOUND));
    assertTrue(errors.isEmpty(), errors.toString());
    assertEquals(launches, received.size());
  }

  @Test
  void closeRemovesTheEndpointAndReleasesTheLock() throws Exception {
    startPrimary();
    assertTrue(Files.exists(temp.resolve(Endpoint.FILE)));
    server.close();
    server.close();
    assertFalse(Files.exists(temp.resolve(Endpoint.FILE)));
    server = InstanceServer.claim(temp);
    assertNotNull(server);
  }

  @Test
  void theEndpointIsOwnerOnly() throws Exception {
    startPrimary();
    Path endpoint = temp.resolve(Endpoint.FILE);
    if (endpoint.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertEquals(
          "rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(endpoint)));
    }
  }

  @Test
  void openLinesMustNameAbsolutePaths() {
    assertNull(
        InstanceServer.parseOpen(
            Protocol.encode("relative.log".getBytes(StandardCharsets.UTF_8)) + " 0"));
    assertNull(InstanceServer.parseOpen("not base64! 0"));
    assertNull(InstanceServer.parseOpen(Protocol.encode(new byte[] {(byte) 0xff, '/'}) + " 0"));
    Path absolute = temp.resolve("x.log").toAbsolutePath();
    assertEquals(
        new FileRequest(absolute, 7),
        InstanceServer.parseOpen(
            Protocol.encode(absolute.toString().getBytes(StandardCharsets.UTF_8)) + " 7"));
    assertNull(
        InstanceServer.parseOpen(
            Protocol.encode(absolute.toString().getBytes(StandardCharsets.UTF_8)) + " -1"));
  }

  @Test
  void aSharedOrForeignDirectoryTurnsTheHandoffOff() throws Exception {
    if (!temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      return;
    }
    Path shared = Files.createDirectory(temp.resolve("shared"));
    Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwx---"));
    assertNotNull(InstanceClient.refusal(shared, System.getProperty("user.name")));
    Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwx------"));
    assertNull(InstanceClient.refusal(shared, System.getProperty("user.name")));
    assertNotNull(InstanceClient.refusal(shared, "qlogg-mock-nobody"));
  }

  /**
   * A primary killed with SIGKILL (or TerminateProcess) leaves its endpoint behind; the OS releases
   * its lock, so the next launch becomes the primary instead of handing off to a dead port.
   */
  @Test
  void aKilledPrimaryReleasesTheLock() throws Exception {
    String java = ProcessHandle.current().info().command().orElse(null);
    if (java == null) {
      return;
    }
    Process child =
        new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                ClaimMain.class.getName(),
                temp.toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    try (BufferedReader out =
        new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
      assertEquals(ClaimMain.READY, out.readLine());
      assertNull(InstanceServer.claim(temp));
      child.destroyForcibly();
      assertTrue(child.waitFor(BOUND.toSeconds(), TimeUnit.SECONDS));
    }
    assertTrue(Files.exists(temp.resolve(Endpoint.FILE)));
    // Windows releases a terminated process's locks shortly after the process ends.
    long deadline = System.nanoTime() + BOUND.toNanos();
    InstanceServer claimed = InstanceServer.claim(temp);
    while (claimed == null && System.nanoTime() < deadline) {
      Thread.sleep(50);
      claimed = InstanceServer.claim(temp);
    }
    startPrimary(claimed);
    InstanceClient.handOff(temp, files("after.log"), false);
    assertEquals(1, received.size());
  }
}
