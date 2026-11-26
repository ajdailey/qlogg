package io.github.ajdailey.qlogg.app.ipc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Each limit of the handoff server against a peer that breaks it: the peer is refused, nothing is
 * opened, and the next launch is served. A refused peer that sent a whole request would otherwise
 * read {@code OK} or {@code ERR}, so a closed connection is the server's refusal, not its deadline.
 */
class HandoffLimitsTest {
  /** Bounds every read of a {@link Peer}. */
  private static final Duration BOUND = Duration.ofSeconds(20);

  /** Path bytes whose {@code OPEN} line with a two-digit line number is exactly the line limit. */
  private static final int LONG_PATH_BYTES = (Protocol.MAX_LINE - 8) / 4 * 3;

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final List<List<FileRequest>> received = new CopyOnWriteArrayList<List<FileRequest>>();
  private Workers workers;
  private InstanceServer server;

  @BeforeEach
  void setUp() throws IOException {
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    server = InstanceServer.claim(temp);
    assertNotNull(server);
    assertTrue(server.start(workers));
    server.deliverTo(
        new InstanceServer.Receiver() {
          @Override
          public boolean deliver(List<FileRequest> files, boolean follow) {
            received.add(files);
            return true;
          }
        });
  }

  @AfterEach
  void tearDown() {
    server.close();
    assertTrue(workers.close(Workers.CLOSE_BOUND));
    assertTrue(failures.isEmpty(), failures.toString());
  }

  /** A raw loopback connection to the server; every read ends within {@link #BOUND}. */
  private static final class Peer implements AutoCloseable {
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    Peer(int port) throws IOException {
      socket = new Socket(InetAddress.getLoopbackAddress(), port);
      socket.setSoTimeout((int) BOUND.toMillis());
      in = socket.getInputStream();
      out = socket.getOutputStream();
    }

    /** Runs HELLO, PROOF and AUTH as a client holding the token. */
    void authenticate(byte[] token) throws IOException {
      byte[] nonceC = Protocol.secret();
      send(ascii(Protocol.HELLO + Protocol.encode(nonceC) + "\n"));
      String proof = readLine();
      assertNotNull(proof);
      assertTrue(proof.startsWith(Protocol.PROOF), proof);
      byte[] nonceS = Protocol.decode(proof.substring(Protocol.PROOF.length()).split(" ")[0]);
      byte[] auth = Protocol.proof(token, "client", nonceS, nonceC);
      send(ascii(Protocol.AUTH + Protocol.encode(auth) + "\n"));
    }

    /** Writes bytes; a server that closed the connection first ends the write. */
    void send(byte[] bytes) throws IOException {
      try {
        out.write(bytes);
        out.flush();
      } catch (SocketException e) {
        // The server refused; readLine then returns null.
      }
    }

    /**
     * Reads one line without its LF.
     *
     * @return the line; null when the server closed or reset the connection first
     * @throws java.net.SocketTimeoutException when nothing arrives within {@link #BOUND}
     */
    String readLine() throws IOException {
      ByteArrayOutputStream line = new ByteArrayOutputStream();
      try {
        int b = in.read();
        while (b >= 0) {
          if (b == '\n') {
            return line.toString(StandardCharsets.US_ASCII);
          }
          line.write(b);
          b = in.read();
        }
        return null;
      } catch (SocketException e) {
        return null;
      }
    }

    /**
     * Reads the end of the stream. A server that closes with unread request bytes resets the
     * connection, and Windows then discards a reply the peer has not read.
     */
    void assertClosedWithoutReset() throws IOException {
      try {
        assertEquals(-1, in.read());
      } catch (SocketException e) {
        throw new AssertionError("Reset after the reply", e);
      }
    }

    @Override
    public void close() throws IOException {
      socket.close();
    }
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

  private static String openLine(Path path, long line) {
    return Protocol.OPEN
        + Protocol.encode(path.toString().getBytes(StandardCharsets.UTF_8))
        + " "
        + line;
  }

  /** A path in the temporary folder of exactly {@link #LONG_PATH_BYTES} UTF-8 bytes. */
  private Path longPath() {
    int prefix = temp.resolve("a").toString().getBytes(StandardCharsets.UTF_8).length - 1;
    Path path = temp.resolve("a".repeat(LONG_PATH_BYTES - prefix));
    assertEquals(LONG_PATH_BYTES, path.toString().getBytes(StandardCharsets.UTF_8).length);
    return path;
  }

  /** Sends an authenticated request of FOLLOW 0, the bytes and END, and reads the reply. */
  private String request(byte[] body) throws IOException {
    Endpoint endpoint = Endpoint.read(temp);
    try (Peer peer = new Peer(endpoint.port())) {
      peer.authenticate(endpoint.token());
      peer.send(followAndEnd(body));
      return peer.readLine();
    }
  }

  private static byte[] followAndEnd(byte[] body) throws IOException {
    ByteArrayOutputStream request = new ByteArrayOutputStream();
    request.write(ascii(Protocol.FOLLOW + "0\n"));
    request.write(body);
    request.write(ascii(Protocol.END + "\n"));
    return request.toByteArray();
  }

  /** Sends an authenticated request, reads {@code ERR} and then the end of the stream. */
  private void assertRefusedWithoutReset(byte[] request) throws IOException {
    Endpoint endpoint = Endpoint.read(temp);
    try (Peer peer = new Peer(endpoint.port())) {
      peer.authenticate(endpoint.token());
      peer.send(request);
      assertEquals(Protocol.ERR, peer.readLine());
      peer.assertClosedWithoutReset();
    }
  }

  private static byte[] lines(List<String> lines) {
    StringBuilder text = new StringBuilder();
    for (String line : lines) {
      text.append(line).append('\n');
    }
    return ascii(text.toString());
  }

  /** Hands off one file as a launch does and checks it arrives. */
  private void assertTheNextLaunchIsServed() throws IOException {
    int before = received.size();
    List<FileRequest> next = List.of(new FileRequest(temp.resolve("next.log"), 0));
    InstanceClient.handOff(temp, next, false);
    assertEquals(before + 1, received.size());
    assertEquals(next, received.get(before));
  }

  @Test
  void aLineOverTheLimitClosesTheConnection() throws Exception {
    Path path = longPath();
    String limit = openLine(path, 10);
    assertEquals(Protocol.MAX_LINE, limit.length());
    assertEquals(Protocol.OK, request(lines(List.of(limit))));
    assertEquals(List.of(List.of(new FileRequest(path, 10))), received);
    assertNull(request(lines(List.of(openLine(path, 100)))));
    assertEquals(1, received.size());
    assertTheNextLaunchIsServed();
  }

  @Test
  void aNonAsciiByteClosesTheConnection() throws Exception {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(ascii(Protocol.OPEN + "x"));
    body.write(new byte[] {(byte) 0xc3, (byte) 0xa9});
    body.write(ascii(" 0\n"));
    assertNull(request(body.toByteArray()));
    assertTrue(received.isEmpty());
    assertTheNextLaunchIsServed();
  }

  @Test
  void aRequestOverTheSizeLimitClosesTheConnection() throws Exception {
    int count = (int) (Protocol.MAX_REQUEST / (Protocol.MAX_LINE + 1)) + 1;
    assertTrue(count <= Protocol.MAX_OPEN);
    List<String> open = new ArrayList<String>();
    String line = openLine(longPath(), 10);
    for (int i = 0; i < count; i++) {
      open.add(line);
    }
    assertNull(request(lines(open)));
    assertTrue(received.isEmpty());
    assertTheNextLaunchIsServed();
  }

  @Test
  void moreOpenLinesThanTheLimitAreAnsweredErr() throws Exception {
    List<String> open = new ArrayList<String>();
    List<FileRequest> most = new ArrayList<FileRequest>();
    for (int i = 0; i <= Protocol.MAX_OPEN; i++) {
      Path path = temp.resolve("f" + i + ".log");
      open.add(openLine(path, 0));
      if (i < Protocol.MAX_OPEN) {
        most.add(new FileRequest(path, 0));
      }
    }
    assertRefusedWithoutReset(followAndEnd(lines(open)));
    assertTrue(received.isEmpty());
    InstanceClient.handOff(temp, most, false);
    assertEquals(List.of(most), received);
    assertTheNextLaunchIsServed();
  }

  /** A malformed FOLLOW line is refused after the rest of the request is read. */
  @Test
  void aMalformedLineIsRefusedAfterTheRestOfTheRequest() throws Exception {
    List<String> request = new ArrayList<String>();
    request.add(Protocol.FOLLOW + "2");
    for (int i = 0; i < Protocol.MAX_OPEN; i++) {
      request.add(openLine(temp.resolve("f" + i + ".log"), 0));
    }
    request.add(Protocol.END);
    assertRefusedWithoutReset(lines(request));
    assertTrue(received.isEmpty());
    assertTheNextLaunchIsServed();
  }

  /** A peer that sends a malformed line and never END gets ERR at the exchange bound. */
  @Test
  void aMalformedRequestWithoutEndIsRefusedAtTheExchangeBound() throws Exception {
    Endpoint endpoint = Endpoint.read(temp);
    try (Peer peer = new Peer(endpoint.port())) {
      peer.authenticate(endpoint.token());
      peer.send(lines(List.of(Protocol.FOLLOW + "2")));
      assertEquals(Protocol.ERR, peer.readLine());
    }
    assertTrue(received.isEmpty());
    assertTheNextLaunchIsServed();
  }

  /**
   * Peers that connect and send nothing hold every slot, so a launch waits in the backlog and ends
   * in its own 1 s bound, well inside the 5 s the server gives each peer. The server then closes
   * each silent peer without a reply and serves the next launch.
   */
  @Test
  void silentPeersHoldingEverySlotAreClosedAndTheNextLaunchIsServed() throws Exception {
    Endpoint endpoint = Endpoint.read(temp);
    List<Peer> silent = new ArrayList<Peer>();
    try {
      for (int i = 0; i < InstanceServer.MAX_CONNECTIONS; i++) {
        silent.add(new Peer(endpoint.port()));
      }
      IOException waited =
          assertThrows(
              IOException.class,
              new Executable() {
                @Override
                public void execute() throws Throwable {
                  InstanceClient.handOff(
                      temp,
                      List.of(new FileRequest(temp.resolve("waited.log"), 0)),
                      false,
                      Duration.ofSeconds(1));
                }
              });
      assertEquals("Timed out", waited.getMessage());
      for (Peer peer : silent) {
        assertNull(peer.readLine());
      }
    } finally {
      for (Peer peer : silent) {
        peer.close();
      }
    }
    assertTrue(received.isEmpty());
    assertTheNextLaunchIsServed();
  }
}
