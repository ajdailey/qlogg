package io.github.ajdailey.qlogg.app.ipc;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import io.github.ajdailey.qlogg.core.text.LogText;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The primary instance's side of the single-instance handoff. {@link #claim} takes the OS lock on
 * {@code instance.lock}, which the OS releases when the process dies, so a stale {@code
 * instance.endpoint} never decides liveness: the holder deletes any endpoint file, binds an
 * ephemeral port on the loopback address and writes the new endpoint with a fresh token.
 *
 * <p>{@link #start} accepts on a {@code qlogg-ipc-} thread and serves each connection on its own,
 * at most {@link #MAX_CONNECTIONS} at once (more are closed on accept), each within {@link
 * #EXCHANGE_BOUND}. A peer that fails authentication gets no reply and no file is opened. Each path
 * must be absolute; the {@link Receiver} opens it like a command-line path. {@link #close} stops
 * accepting, deletes the endpoint, then releases the lock, in that order, so a new primary never
 * loses its own endpoint file. Close it before {@link Workers#close}, which waits for the accept
 * thread.
 */
public final class InstanceServer implements AutoCloseable {
  /** Receives the files of a handoff. */
  public interface Receiver {
    /**
     * Takes the files; called on a {@code qlogg-ipc-} thread.
     *
     * @param files absolute paths with their {@code +line:N}
     * @param follow {@code -f} of the launch that sent them
     * @return false when the instance is quitting and takes nothing
     */
    boolean deliver(List<FileRequest> files, boolean follow);
  }

  /** Most connections served at once. */
  public static final int MAX_CONNECTIONS = 4;

  /** Longest exchange with one client. */
  public static final Duration EXCHANGE_BOUND = Duration.ofSeconds(5);

  private static final Logger LOG = LoggerFactory.getLogger(InstanceServer.class);

  private final Path directory;
  private final FileChannel lockChannel;
  private final FileLock lock;
  private final ServerSocketChannel server;
  private final byte[] token;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicInteger connections = new AtomicInteger();

  private InstanceServer(
      Path directory,
      FileChannel lockChannel,
      FileLock lock,
      ServerSocketChannel server,
      byte[] token) {
    this.directory = directory;
    this.lockChannel = lockChannel;
    this.lock = lock;
    this.server = server;
    this.token = token;
  }

  /**
   * Becomes the primary instance for a settings directory when no live instance is.
   *
   * @param directory the settings directory
   * @return the server, bound, its endpoint written; null when a live instance holds the lock
   * @throws IOException when the lock file, the port or the endpoint file fails
   */
  public static InstanceServer claim(Path directory) throws IOException {
    FileChannel channel =
        FileChannel.open(
            SafePaths.child(directory, Endpoint.LOCK),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    InstanceServer claimed = null;
    try {
      FileLock lock;
      try {
        lock = channel.tryLock();
      } catch (OverlappingFileLockException e) {
        lock = null;
      }
      if (lock == null) {
        return null;
      }
      ServerSocketChannel server = null;
      try {
        Endpoint.delete(directory);
        server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), MAX_CONNECTIONS);
        int port = ((InetSocketAddress) server.getLocalAddress()).getPort();
        byte[] token = Protocol.secret();
        new Endpoint(port, token).write(directory);
        claimed = new InstanceServer(directory, channel, lock, server, token);
        return claimed;
      } finally {
        if (claimed == null && server != null) {
          server.close();
        }
      }
    } finally {
      if (claimed == null) {
        channel.close();
      }
    }
  }

  /**
   * Starts accepting handoffs.
   *
   * @param workers starts the {@code qlogg-ipc-} threads
   * @param receiver takes the files
   * @return false when the workers are closed
   */
  public boolean start(final Workers workers, final Receiver receiver) {
    return workers.tryStart(
        Workers.Kind.IPC,
        new Runnable() {
          @Override
          public void run() {
            accept(workers, receiver);
          }
        });
  }

  private void accept(Workers workers, final Receiver receiver) {
    while (!closed.get()) {
      final SocketChannel connection;
      try {
        connection = server.accept();
      } catch (ClosedChannelException e) {
        return;
      } catch (IOException e) {
        LOG.warn("Handoff listener stopped: {}", LogText.oneLine(e.getMessage()));
        return;
      }
      if (connections.incrementAndGet() > MAX_CONNECTIONS
          || !workers.tryStart(
              Workers.Kind.IPC,
              new Runnable() {
                @Override
                public void run() {
                  try {
                    serve(connection, receiver);
                  } finally {
                    connections.decrementAndGet();
                  }
                }
              })) {
        connections.decrementAndGet();
        closeQuietly(connection);
      }
    }
  }

  private void serve(SocketChannel connection, Receiver receiver) {
    long deadline = System.nanoTime() + EXCHANGE_BOUND.toNanos();
    try (LineChannel line = LineChannel.accepted(connection, deadline)) {
      if (!authenticate(line)) {
        LOG.warn("Handoff refused: the peer did not prove the token");
        return;
      }
      List<FileRequest> files = new ArrayList<FileRequest>();
      Request request = readRequest(line, files);
      boolean taken = request != null && receiver.deliver(files, request.follow());
      line.writeLine(taken ? Protocol.OK : Protocol.ERR);
      if (request == null) {
        LOG.warn("Handoff refused: malformed request");
      } else if (LOG.isInfoEnabled()) {
        LOG.info("Handoff of {} files {}", files.size(), taken ? "taken" : "refused");
      }
    } catch (IOException e) {
      LOG.debug("Handoff connection ended: {}", LogText.oneLine(e.getMessage()));
    }
  }

  /** Runs HELLO, PROOF and AUTH; false when the peer's proof is wrong. */
  private boolean authenticate(LineChannel line) throws IOException {
    String hello = line.readLine();
    if (!hello.startsWith(Protocol.HELLO)) {
      return false;
    }
    byte[] nonceC = Protocol.decode(hello.substring(Protocol.HELLO.length()));
    if (nonceC.length != Protocol.SECRET_BYTES) {
      return false;
    }
    byte[] nonceS = Protocol.secret();
    line.writeLine(
        Protocol.PROOF
            + Protocol.encode(nonceS)
            + " "
            + Protocol.encode(Protocol.proof(token, "server", nonceC, nonceS)));
    String auth = line.readLine();
    return auth.startsWith(Protocol.AUTH)
        && Protocol.matches(
            Protocol.proof(token, "client", nonceS, nonceC),
            Protocol.decode(auth.substring(Protocol.AUTH.length())));
  }

  /** A well-formed request's {@code FOLLOW} flag. */
  private record Request(boolean follow) {}

  /**
   * Reads FOLLOW, the OPEN lines and END.
   *
   * @return the request, or null when a line is malformed
   */
  private static Request readRequest(LineChannel line, List<FileRequest> files) throws IOException {
    String follow = line.readLine();
    if (!follow.equals(Protocol.FOLLOW + "0") && !follow.equals(Protocol.FOLLOW + "1")) {
      return null;
    }
    while (true) {
      String next = line.readLine();
      if (next.equals(Protocol.END)) {
        return new Request(follow.endsWith("1"));
      }
      if (!next.startsWith(Protocol.OPEN) || files.size() == Protocol.MAX_OPEN) {
        return null;
      }
      FileRequest request = parseOpen(next.substring(Protocol.OPEN.length()));
      if (request == null) {
        return null;
      }
      files.add(request);
    }
  }

  /** Parses {@code <base64url path> <line>}; null when malformed or not absolute. */
  static FileRequest parseOpen(String text) {
    int space = text.indexOf(' ');
    if (space < 0) {
      return null;
    }
    byte[] bytes = Protocol.decode(text.substring(0, space));
    try {
      String name =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      long number = Long.parseLong(text.substring(space + 1));
      Path path = Path.of(name);
      if (number < 0 || name.indexOf('\0') >= 0 || !path.isAbsolute()) {
        return null;
      }
      return new FileRequest(path, number);
    } catch (CharacterCodingException | NumberFormatException | InvalidPathException e) {
      return null;
    }
  }

  /**
   * Stops accepting, deletes the endpoint file and releases the lock; a second call does nothing.
   * Callable from any thread.
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    closeQuietly(server);
    try {
      Endpoint.delete(directory);
    } catch (IOException e) {
      LOG.warn("Cannot delete the handoff endpoint: {}", LogText.oneLine(e.getMessage()));
    }
    try {
      lock.release();
    } catch (IOException e) {
      LOG.debug("Cannot release the instance lock: {}", LogText.oneLine(e.getMessage()));
    }
    closeQuietly(lockChannel);
  }

  private static void closeQuietly(Channel channel) {
    try {
      channel.close();
    } catch (IOException e) {
      LOG.debug("Close failed: {}", LogText.oneLine(e.getMessage()));
    }
  }
}
