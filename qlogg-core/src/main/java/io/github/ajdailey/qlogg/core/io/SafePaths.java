package io.github.ajdailey.qlogg.core.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation for every path that comes from outside the process. A log viewer opens whatever
 * readable file its user names, so a log path is canonicalized and its file type checked rather
 * than contained; files QLogg owns are contained in their directory.
 */
public final class SafePaths {
  private static final Pattern WINDOWS_RESERVED =
      Pattern.compile(
          "(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]|CONIN\\$|CONOUT\\$)(\\..*)?",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern CHILD_NAME = Pattern.compile("[A-Za-z0-9._-]+");
  private static final Set<PosixFilePermission> OWNER_DIR =
      PosixFilePermissions.fromString("rwx------");

  private static volatile HeldLock heldLock;

  private SafePaths() {}

  /** The file this process holds its instance lock on. */
  private record HeldLock(Path real, Object fileKey, String refusal) {}

  /**
   * Names the file this process holds its instance lock on. {@link #logPath(Path)} then rejects it
   * under any name, hard links included, so nothing reads or replaces it: on POSIX a close of any
   * channel on the file ends the process's lock, and a replace of the name leaves the lock on the
   * old file.
   *
   * @param file the lock file
   * @param refusal the message of the rejection, shown to the user
   * @throws IOException when the file cannot be read
   */
  public static void holdLock(Path file, String refusal) throws IOException {
    Object key = Files.readAttributes(file, BasicFileAttributes.class).fileKey();
    heldLock = new HeldLock(file.toRealPath(), key, refusal);
  }

  /** Forgets the file named to {@link #holdLock}. */
  public static void releaseLock() {
    heldLock = null;
  }

  private static void checkNotHeld(Path real) throws IOException {
    HeldLock held = heldLock;
    if (held == null) {
      return;
    }
    Object key = held.fileKey();
    if (real.equals(held.real())
        || (key != null
            && key.equals(Files.readAttributes(real, BasicFileAttributes.class).fileKey()))) {
      throw new IOException(held.refusal());
    }
  }

  /**
   * A validated log path.
   *
   * @param given the path as given, absolute and normalized; symbolic links are kept
   * @param real the real path of an existing file, or the missing file under its parent's real path
   */
  public record LogPath(Path given, Path real) {}

  /**
   * Validates a log file path from the command line, a handoff, the recent list or a session. The
   * path is made absolute and normalized; an existing path resolves to its real path and must be a
   * regular file (a FIFO blocks the reader and a device never ends) and not the file named to
   * {@link #holdLock}; a missing path needs an existing parent directory, so a log that appears
   * later can be watched. Validate again on every open: a symbolic link can be retargeted between
   * two opens.
   *
   * @param path the path as given; a relative path resolves against the working directory
   * @return the absolute path as given and the real path
   * @throws IOException naming the reason when the path is rejected
   */
  public static LogPath logPath(Path path) throws IOException {
    checkForm(path.toString());
    Path absolute = path.toAbsolutePath().normalize();
    if (Files.exists(absolute)) {
      Path real = absolute.toRealPath();
      if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException(real + " is not a regular file");
      }
      checkNotHeld(real);
      return new LogPath(absolute, real);
    }
    Path parent = absolute.getParent();
    Path name = absolute.getFileName();
    if (parent == null || name == null || !Files.isDirectory(parent)) {
      throw new IOException(absolute + " does not exist and has no parent directory");
    }
    return new LogPath(absolute, parent.toRealPath().resolve(name));
  }

  /**
   * Validates a log file path as {@link #logPath(Path)} does.
   *
   * @param path the path as given; a relative path resolves against the working directory
   * @return the real path of an existing file, or the absolute path of a missing one under its
   *     parent's real path
   * @throws IOException naming the reason when the path is rejected
   */
  public static Path logFile(Path path) throws IOException {
    return logPath(path).real();
  }

  /**
   * Validates a log file path given as text.
   *
   * @param path the path as given
   * @return as {@link #logFile(Path)}
   * @throws IOException when the text is not a path or the path is rejected
   */
  public static Path logFile(String path) throws IOException {
    checkForm(path);
    try {
      return logFile(Path.of(path));
    } catch (InvalidPathException e) {
      throw new IOException("Not a valid path: " + e.getReason(), e);
    }
  }

  // Search in a folder.

  /**
   * Validates a folder of logs to search: checked as {@link #logFile(String)} checks a path's form,
   * made absolute and normalized, then resolved to its real path, which must be a directory.
   *
   * @param path the folder as given; a relative path resolves against the working directory
   * @return the real path of the directory
   * @throws IOException when the text is not a path, the folder does not exist or is not a
   *     directory
   */
  public static Path logDirectory(String path) throws IOException {
    checkForm(path);
    Path absolute;
    try {
      absolute = Path.of(path).toAbsolutePath().normalize();
    } catch (InvalidPathException e) {
      throw new IOException("Not a valid path: " + e.getReason(), e);
    }
    if (!Files.isDirectory(absolute)) {
      throw new IOException(absolute + " is not a directory");
    }
    return absolute.toRealPath();
  }

  /**
   * Validates a settings directory: made absolute and normalized, created with owner-only
   * permissions where the file system supports POSIX permissions, then resolved to its real path.
   *
   * @param path the directory as given
   * @return the real path of the directory
   * @throws IOException when it cannot be created or is not a directory
   */
  public static Path settingsDirectory(Path path) throws IOException {
    checkForm(path.toString());
    Path absolute = path.toAbsolutePath().normalize();
    if (absolute.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.createDirectories(absolute, PosixFilePermissions.asFileAttribute(OWNER_DIR));
    } else {
      Files.createDirectories(absolute);
    }
    Path real = absolute.toRealPath();
    if (!Files.isDirectory(real)) {
      throw new IOException(real + " is not a directory");
    }
    return real;
  }

  /**
   * Resolves a fixed file name inside a directory QLogg owns.
   *
   * @param base the directory
   * @param name a name matching {@code [A-Za-z0-9._-]+}
   * @return the file, which lies inside base
   * @throws IOException when the name is not a plain name or resolves outside base
   */
  public static Path child(Path base, String name) throws IOException {
    if (name == null
        || !CHILD_NAME.matcher(name).matches()
        || name.equals(".")
        || name.equals("..")) {
      throw new IOException("Not a plain file name");
    }
    Path normalized = base.toAbsolutePath().normalize();
    Path file = normalized.resolve(name).normalize();
    if (!file.startsWith(normalized) || file.equals(normalized)) {
      throw new IOException("File name resolves outside " + normalized);
    }
    return file;
  }

  /**
   * Reports whether a path names a Windows device or uses a device or raw namespace prefix: {@code
   * \\.\}, {@code \\?\}, or a component that is a reserved device name such as {@code CON} or
   * {@code COM1}, with or without an extension.
   *
   * @param path the path text
   * @return true when Windows would open a device rather than a file
   */
  static boolean windowsDevice(String path) {
    String text = path.replace('/', '\\');
    if (text.startsWith("\\\\.\\") || text.startsWith("\\\\?\\")) {
      return true;
    }
    for (String part : text.split("[\\\\:]")) {
      String name = part.strip();
      if (WINDOWS_RESERVED.matcher(name).matches()) {
        return true;
      }
    }
    return false;
  }

  private static void checkForm(String path) throws IOException {
    if (path == null || path.isEmpty()) {
      throw new IOException("Empty path");
    }
    if (path.indexOf('\0') >= 0) {
      throw new IOException("Path contains a NUL character");
    }
    if (File.separatorChar == '\\' && windowsDevice(path)) {
      throw new IOException("Path names a Windows device");
    }
  }
}
