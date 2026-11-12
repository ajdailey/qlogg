package io.github.ajdailey.qlogg.core.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Replaces a file in one step: the content goes to a temporary file in the target's directory,
 * which then moves over the target with {@code ATOMIC_MOVE}. A reader sees the old file or the new
 * one, never a partial write. Nothing goes to {@code java.io.tmpdir}. A target that is a symbolic
 * link is resolved first, dangling or not, so the link stays a link and the file it names is
 * replaced. Where POSIX permissions do not exist (Windows), a move that throws a {@code
 * FileSystemException} is tried up to {@value #MOVE_ATTEMPTS} times, {@value #MOVE_RETRY_MILLIS} ms
 * apart: a target another process holds open without delete sharing refuses the move for that
 * moment.
 *
 * <p>{@link #write} and {@link #writeOwnerOnly} write QLogg's own files, owner-only where POSIX
 * permissions exist. {@link #writeUserFile} writes a file the user names and keeps the target's
 * group and mode.
 */
public final class AtomicFiles {
  private static final Set<PosixFilePermission> OWNER_FILE =
      PosixFilePermissions.fromString("rw-------");

  /** Bytes read from a stream at a time. */
  private static final int COPY_BUFFER = 64 * 1024;

  /** Tries of a refused move on a file system without POSIX permissions. */
  static final int MOVE_ATTEMPTS = 10;

  /** Milliseconds between tries of a refused move. */
  static final long MOVE_RETRY_MILLIS = 25;

  /** Most symbolic links followed from a target. */
  private static final int MAX_LINKS = 40;

  private static final SecureRandom RANDOM = new SecureRandom();

  /** The step {@link #tryMove} repeats. */
  interface Move {
    /**
     * Moves the file.
     *
     * @throws IOException when the move fails
     */
    void run() throws IOException;
  }

  private AtomicFiles() {}

  /**
   * Writes a file atomically.
   *
   * @param file the target, in an existing directory
   * @param content the bytes
   * @throws IOException when the temporary file cannot be written or moved; the target is then
   *     unchanged and the temporary file deleted
   */
  public static void write(Path file, byte[] content) throws IOException {
    write(file, content, null, false);
  }

  /**
   * Writes a file atomically from a stream, which is read to its end and not closed.
   *
   * @param file the target, in an existing directory
   * @param content the bytes
   * @throws IOException when the stream, the temporary file or the move fails; the target is then
   *     unchanged and the temporary file deleted
   */
  public static void write(Path file, InputStream content) throws IOException {
    write(file, null, content, false);
  }

  /**
   * Writes a secret atomically: as {@link #write}, and where the file system has ACLs but no POSIX
   * permissions (Windows), the temporary file's ACL is replaced by one entry for the current user
   * before the content is written.
   *
   * @param file the target, in an existing directory
   * @param content the bytes
   * @throws IOException when the temporary file cannot be written, restricted or moved; the target
   *     is then unchanged and the temporary file deleted
   */
  public static void writeOwnerOnly(Path file, byte[] content) throws IOException {
    write(file, content, null, true);
  }

  /**
   * Writes a file the user names. Replacing a file, the temporary file is owner-only while it is
   * written, takes the target's group, then its POSIX permissions or DOS hidden and system
   * attributes before the move; a new file is created with the default mode. The data is forced to
   * disk before the move.
   *
   * <p>The target is written in place (truncate, write, force) instead when it has more than one
   * hard link, its owner is not the temporary file's owner, the temporary file cannot take its
   * group, its directory is not writable, or the move is still refused after its tries. An in-place
   * write that fails partway leaves the target partly written.
   *
   * @param target the file as the user named it, in an existing directory
   * @param content the bytes
   * @throws AccessDeniedException when the target exists and is not writable; nothing is written
   * @throws IOException when the write fails; an atomic write leaves the target unchanged and
   *     deletes the temporary file
   */
  public static void writeUserFile(Path target, byte[] content) throws IOException {
    Path file = linkEnd(target);
    Path dir = file.getParent();
    if (dir == null) {
      throw new IOException("No parent directory");
    }
    boolean replacing = Files.exists(file);
    if (replacing) {
      if (!Files.isWritable(file)) {
        throw new AccessDeniedException(file.toString(), null, "The file is not writable");
      }
      if (hardLinked(file) || !Files.isWritable(dir)) {
        writeInPlace(file, content);
        return;
      }
    }
    Path temp = dir.resolve(".qlogg-" + randomHex() + ".tmp");
    if (replacing && posix(dir)) {
      Files.createFile(temp, PosixFilePermissions.asFileAttribute(OWNER_FILE));
    } else {
      Files.createFile(temp);
    }
    boolean moved = false;
    try {
      if (replacing && !(sameOwner(file, temp) && sameGroup(file, temp))) {
        writeInPlace(file, content);
        return;
      }
      writeForced(temp, content, StandardOpenOption.WRITE);
      if (replacing) {
        copyAttributes(file, temp);
      }
      moved = moveUserFile(temp, file);
      if (!moved) {
        writeInPlace(file, content);
      }
    } finally {
      if (!moved) {
        Files.deleteIfExists(temp);
      }
    }
  }

  /**
   * Returns where a target's symbolic links end, the absolute target when it is not a link.
   *
   * @param target the target
   * @return the path the last link names
   * @throws IOException when a link cannot be read; {@link FileSystemLoopException} past {@value
   *     #MAX_LINKS} links
   */
  static Path linkEnd(Path target) throws IOException {
    Path end = target.toAbsolutePath();
    for (int links = 0; Files.isSymbolicLink(end); links++) {
      if (links == MAX_LINKS) {
        throw new FileSystemLoopException(target.toString());
      }
      end = end.resolveSibling(Files.readSymbolicLink(end));
    }
    return end;
  }

  private static void write(Path target, byte[] content, InputStream source, boolean restrict)
      throws IOException {
    Path file = linkEnd(target);
    Path dir = file.getParent();
    if (dir == null) {
      throw new IOException("No parent directory");
    }
    Path temp;
    if (posix(dir)) {
      temp =
          Files.createTempFile(
              dir, ".qlogg-", ".tmp", PosixFilePermissions.asFileAttribute(OWNER_FILE));
    } else {
      temp = Files.createTempFile(dir, ".qlogg-", ".tmp");
    }
    boolean moved = false;
    try {
      if (restrict) {
        ownerOnlyAcl(temp);
      }
      try (OutputStream out = Files.newOutputStream(temp)) {
        if (source == null) {
          out.write(content);
        } else {
          byte[] buffer = new byte[COPY_BUFFER];
          int read;
          while ((read = source.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
          }
        }
      }
      replace(temp, file);
      moved = true;
    } finally {
      if (!moved) {
        Files.deleteIfExists(temp);
      }
    }
  }

  private static boolean posix(Path path) {
    return path.getFileSystem().supportedFileAttributeViews().contains("posix");
  }

  private static void replace(final Path temp, final Path file) throws IOException {
    tryMove(
        new Move() {
          @Override
          public void run() throws IOException {
            Files.move(
                temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
          }
        },
        posix(temp) ? 1 : MOVE_ATTEMPTS);
  }

  /**
   * Runs a move, again after a {@code FileSystemException} until the attempts are used.
   *
   * @param move the move
   * @param attempts the most tries
   * @throws IOException the last refusal, or any other failure at once; an interrupt during the
   *     wait ends the tries with the refusal and keeps the interrupt status
   */
  static void tryMove(Move move, int attempts) throws IOException {
    int attempt = 1;
    while (true) {
      try {
        move.run();
        return;
      } catch (FileSystemException e) {
        if (attempt >= attempts) {
          throw e;
        }
        attempt++;
        try {
          Thread.sleep(MOVE_RETRY_MILLIS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
  }

  /**
   * Moves a user file's temporary file over the target.
   *
   * @return false when every try was refused; on an interrupted thread the refusal is thrown
   *     instead, so no in-place write starts
   */
  private static boolean moveUserFile(Path temp, Path file) throws IOException {
    try {
      replace(temp, file);
      return true;
    } catch (FileSystemException e) {
      if (Thread.currentThread().isInterrupted()) {
        throw e;
      }
      return false;
    }
  }

  private static void writeInPlace(Path file, byte[] content) throws IOException {
    writeForced(file, content, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
  }

  private static void writeForced(Path file, byte[] content, StandardOpenOption... options)
      throws IOException {
    try (FileChannel channel = FileChannel.open(file, options)) {
      ByteBuffer buffer = ByteBuffer.wrap(content);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    }
  }

  private static boolean hardLinked(Path file) throws IOException {
    if (!file.getFileSystem().supportedFileAttributeViews().contains("unix")) {
      return false;
    }
    Object links = Files.getAttribute(file, "unix:nlink");
    return links instanceof Integer count && count > 1;
  }

  private static boolean sameOwner(Path file, Path temp) throws IOException {
    if (!file.getFileSystem().supportedFileAttributeViews().contains("owner")) {
      return true;
    }
    return Files.getOwner(file).equals(Files.getOwner(temp));
  }

  /** Gives the temporary file the target's group; false when it cannot. True without POSIX. */
  private static boolean sameGroup(Path file, Path temp) throws IOException {
    PosixFileAttributeView view = Files.getFileAttributeView(temp, PosixFileAttributeView.class);
    if (view == null) {
      return true;
    }
    GroupPrincipal group = Files.readAttributes(file, PosixFileAttributes.class).group();
    if (group.equals(view.readAttributes().group())) {
      return true;
    }
    try {
      view.setGroup(group);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /** Gives the temporary file the target's POSIX permissions, or its DOS hidden and system bits. */
  private static void copyAttributes(Path file, Path temp) throws IOException {
    PosixFileAttributeView posix =
        Files.getFileAttributeView(file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (posix != null) {
      Files.setPosixFilePermissions(temp, posix.readAttributes().permissions());
      return;
    }
    DosFileAttributeView dos = Files.getFileAttributeView(temp, DosFileAttributeView.class);
    if (dos != null) {
      DosFileAttributes from = Files.readAttributes(file, DosFileAttributes.class);
      dos.setHidden(from.isHidden());
      dos.setSystem(from.isSystem());
      dos.setArchive(true);
    }
  }

  private static String randomHex() {
    byte[] bytes = new byte[6];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  /** Leaves one ACL entry, full access for the user, where POSIX permissions do not exist. */
  private static void ownerOnlyAcl(Path temp) throws IOException {
    if (posix(temp)) {
      return;
    }
    AclFileAttributeView view = Files.getFileAttributeView(temp, AclFileAttributeView.class);
    if (view == null) {
      return;
    }
    AclEntry entry =
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(currentUser(temp.getFileSystem(), view))
            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
            .build();
    view.setAcl(List.of(entry));
  }

  /**
   * Returns the user running this process. A new file's owner is not that user in an elevated
   * process, where Windows makes {@code BUILTIN\Administrators} the owner. The name is qualified
   * first because a bare name resolves to a local account before a domain account of that name.
   *
   * @return the user; the file's owner when no name resolves, as under a service account or with a
   *     domain controller out of reach
   */
  private static UserPrincipal currentUser(FileSystem system, AclFileAttributeView view)
      throws IOException {
    UserPrincipalLookupService lookup = system.getUserPrincipalLookupService();
    String name = System.getProperty("user.name");
    String domain = System.getenv("USERDOMAIN");
    if (domain != null) {
      try {
        return lookup.lookupPrincipalByName(domain + "\\" + name);
      } catch (IOException e) {
        // The bare name below still applies.
      }
    }
    try {
      return lookup.lookupPrincipalByName(name);
    } catch (IOException e) {
      return view.getOwner();
    }
  }
}
