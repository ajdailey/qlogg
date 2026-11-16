package io.github.ajdailey.qlogg.app.ipc;

import com.sun.nio.file.ExtendedOpenOption;
import io.github.ajdailey.qlogg.core.io.Accounts;
import io.github.ajdailey.qlogg.core.util.Streams;
import io.github.ajdailey.qlogg.core.util.os.EnvPaths;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Reads {@code instance.endpoint} only when no other account can have written it: a regular file,
 * read without following links, owned by the current user and open to no other account. On POSIX
 * that is no group or other permission bit; where there are ACLs instead (Windows), every allow
 * entry is for an account {@link AclCheck} trusts. A file system with neither checks the file type
 * only. The directory is not checked: another account that can write it can delete or rename the
 * endpoint, but cannot create a file the user owns.
 *
 * <p>The attributes are read before the content and again while the file is open, and must name the
 * same file. On Windows the file is opened without delete sharing, so it cannot be renamed while
 * open; on POSIX the file key and {@code ctime} must match, and a rename sets {@code ctime}.
 */
final class EndpointFile {
  private static final Set<PosixFilePermission> OTHERS =
      EnumSet.of(
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.GROUP_WRITE,
          PosixFilePermission.GROUP_EXECUTE,
          PosixFilePermission.OTHERS_READ,
          PosixFilePermission.OTHERS_WRITE,
          PosixFilePermission.OTHERS_EXECUTE);

  /** What identifies the file between the two attribute reads. */
  private record Seen(Object key, Object changed) {}

  private EndpointFile() {}

  /**
   * Reads the endpoint for the current user.
   *
   * @param file the endpoint file
   * @param limit the most bytes
   * @return the content
   * @throws NoSuchFileException when there is no file
   * @throws IOException when it is not trusted or cannot be read, naming the step and its exception
   */
  static byte[] read(Path file, int limit) throws IOException {
    return read(
        file,
        limit,
        System.getProperty("user.name"),
        EnvPaths.absolute(System.getenv("USERPROFILE")));
  }

  /**
   * Reads the endpoint for a user.
   *
   * @param file the endpoint file
   * @param limit the most bytes
   * @param user the user's name
   * @param profile the user's profile directory, from {@code USERPROFILE}; read only where there
   *     are ACLs, null when it is not an absolute path
   * @return the content
   * @throws NoSuchFileException when there is no file
   * @throws IOException when it is not trusted or cannot be read, naming the step and its exception
   */
  static byte[] read(Path file, int limit, String user, Path profile) throws IOException {
    if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new NoSuchFileException(file.toString());
    }
    FileSystem system = file.getFileSystem();
    Set<String> views = system.supportedFileAttributeViews();
    boolean posix = views.contains("posix");
    boolean acl = !posix && views.contains("acl");
    UserPrincipal me = null;
    Set<UserPrincipal> trusted = null;
    if (posix || acl) {
      try {
        me = Accounts.user(system, user);
      } catch (IOException e) {
        throw new IOException("the user name " + user + " does not resolve: " + e, e);
      }
      trusted = new HashSet<UserPrincipal>();
      trusted.add(me);
      if (acl) {
        if (profile == null) {
          throw new IOException("USERPROFILE is not an absolute path");
        }
        trusted.addAll(AclCheck.trusted(profile));
      }
    }
    Seen before = check(file, posix, acl, me, trusted);
    byte[] bytes;
    Seen after;
    try (SeekableByteChannel channel = Files.newByteChannel(file, options(acl))) {
      bytes = Streams.readLimited(Channels.newInputStream(channel), limit);
      after = check(file, posix, acl, me, trusted);
    } catch (EndpointRefusal e) {
      throw e;
    } catch (IOException e) {
      throw new IOException("the endpoint file cannot be read: " + e, e);
    }
    if (!before.equals(after)) {
      throw new IOException("the endpoint file was replaced while it was read");
    }
    return bytes;
  }

  /** Opens without following links; on Windows also without delete sharing. */
  private static Set<OpenOption> options(boolean acl) {
    Set<OpenOption> options = new HashSet<OpenOption>();
    options.add(StandardOpenOption.READ);
    options.add(LinkOption.NOFOLLOW_LINKS);
    if (acl) {
      options.add(ExtendedOpenOption.NOSHARE_DELETE);
    }
    return options;
  }

  /** Checks the file's type, owner and access, and returns what identifies it. */
  private static Seen check(
      Path file, boolean posix, boolean acl, UserPrincipal me, Set<UserPrincipal> trusted)
      throws EndpointRefusal {
    try {
      Object changed =
          posix && file.getFileSystem().supportedFileAttributeViews().contains("unix")
              ? Files.getAttribute(file, "unix:ctime", LinkOption.NOFOLLOW_LINKS)
              : null;
      BasicFileAttributes basic =
          posix
              ? Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
              : Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!basic.isRegularFile()) {
        throw new EndpointRefusal("the endpoint file is not a regular file");
      }
      if (basic instanceof PosixFileAttributes attributes) {
        if (!attributes.owner().equals(me)) {
          throw new EndpointRefusal(
              "the endpoint file belongs to " + attributes.owner().getName() + ", not the user");
        }
        Set<PosixFilePermission> open = EnumSet.copyOf(OTHERS);
        open.retainAll(attributes.permissions());
        if (!open.isEmpty()) {
          throw new EndpointRefusal("the endpoint file is open to other accounts: " + open);
        }
      } else if (acl) {
        String refusal = AclCheck.refusal(file, me, trusted);
        if (refusal != null) {
          throw new EndpointRefusal(refusal);
        }
      }
      return new Seen(basic.fileKey(), changed);
    } catch (EndpointRefusal e) {
      throw e;
    } catch (IOException e) {
      throw new EndpointRefusal("the endpoint file's attributes cannot be read: " + e, e);
    }
  }

  /** A refusal whose message already names its step. */
  private static final class EndpointRefusal extends IOException {
    private static final long serialVersionUID = 1L;

    EndpointRefusal(String message) {
      super(message);
    }

    EndpointRefusal(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
