package io.github.ajdailey.qlogg.app.ipc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.core.io.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The endpoint file is trusted by its own owner and access, not by its directory: in a directory
 * other accounts can write, they can delete or rename the endpoint but cannot create one the user
 * owns.
 */
class EndpointFileTest {
  private static final byte[] CONTENT = "version=1\n".getBytes(StandardCharsets.US_ASCII);

  @TempDir Path temp;

  private static void assumePosix(Path path) {
    assumeTrue(path.getFileSystem().supportedFileAttributeViews().contains("posix"));
  }

  /** Writes the endpoint as the primary does, in a directory with the given POSIX mode. */
  private Path endpointIn(String directoryMode) throws IOException {
    Path directory = Files.createDirectory(temp.resolve("settings"));
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString(directoryMode));
    Path file = directory.resolve(Endpoint.FILE);
    AtomicFiles.writeOwnerOnly(file, CONTENT);
    return file;
  }

  /** Reads as the current user and asserts the refusal names the step. */
  private static void assertRefused(Path file, String step) {
    assertRefused(file, null, step);
  }

  /** Reads as {@code user}, or the current user when null, without a profile directory. */
  private static void assertRefused(final Path file, final String user, String step) {
    IOException refused =
        assertThrows(
            IOException.class,
            new Executable() {
              @Override
              public void execute() throws IOException {
                if (user == null) {
                  EndpointFile.read(file, 4096);
                } else {
                  EndpointFile.read(file, 4096, user, null);
                }
              }
            });
    assertTrue(refused.getMessage().contains(step), refused.getMessage());
  }

  @Test
  void anOwnerOnlyEndpointInAGroupWritableDirectoryIsRead() throws IOException {
    assumePosix(temp);
    Path file = endpointIn("rwxrwxr-x");
    assertArrayEquals(CONTENT, EndpointFile.read(file, 4096));
  }

  @Test
  void anEndpointWithAGroupOrOtherBitIsRefused() throws IOException {
    assumePosix(temp);
    Path file = endpointIn("rwx------");
    Set<PosixFilePermission> others =
        EnumSet.complementOf(
            EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
    for (PosixFilePermission bit : others) {
      Set<PosixFilePermission> mode =
          EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, bit);
      Files.setPosixFilePermissions(file, mode);
      assertRefused(file, "open to other accounts");
    }
  }

  @Test
  void aSymbolicLinkToAnOwnerOnlyEndpointIsRefused() throws IOException {
    assumePosix(temp);
    Path target = endpointIn("rwx------");
    Path link = Files.createSymbolicLink(temp.resolve(Endpoint.FILE), target);
    assertRefused(link, "not a regular file");
  }

  @Test
  void aDirectoryNamedAsTheEndpointIsRefused() throws IOException {
    Path directory = Files.createDirectory(temp.resolve(Endpoint.FILE));
    assertRefused(directory, "not a regular file");
  }

  @Test
  void anEndpointOfAnotherUserIsRefused() throws IOException {
    assumePosix(temp);
    Path file = endpointIn("rwx------");
    assertRefused(file, "root", "belongs to");
    assertRefused(file, "qlogg-mock-nobody", "does not resolve");
  }

  @Test
  void aMissingEndpointIsReportedAsMissing() {
    final Path file = temp.resolve(Endpoint.FILE);
    assertThrows(
        NoSuchFileException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            EndpointFile.read(file, 4096);
          }
        });
  }

  /**
   * A portable installation under {@code C:\} inherits {@code Authenticated Users: Modify}. The
   * endpoint written owner-only there is read; a file that took the directory's entries is not.
   */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void anOwnerOnlyEndpointInADirectoryAuthenticatedUsersCanModifyIsRead() throws IOException {
    UserPrincipal users = group("NT AUTHORITY\\Authenticated Users");
    Path directory = Files.createDirectory(temp.resolve("settings"));
    AclFileAttributeView view = Files.getFileAttributeView(directory, AclFileAttributeView.class);
    List<AclEntry> acl = new ArrayList<AclEntry>(view.getAcl());
    acl.add(
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(users)
            .setPermissions(
                AclEntryPermission.READ_DATA,
                AclEntryPermission.WRITE_DATA,
                AclEntryPermission.APPEND_DATA,
                AclEntryPermission.DELETE,
                AclEntryPermission.DELETE_CHILD,
                AclEntryPermission.EXECUTE,
                AclEntryPermission.READ_ATTRIBUTES,
                AclEntryPermission.WRITE_ATTRIBUTES,
                AclEntryPermission.READ_NAMED_ATTRS,
                AclEntryPermission.WRITE_NAMED_ATTRS,
                AclEntryPermission.READ_ACL,
                AclEntryPermission.SYNCHRONIZE)
            .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
            .build());
    view.setAcl(acl);
    Path file = directory.resolve(Endpoint.FILE);
    AtomicFiles.writeOwnerOnly(file, CONTENT);
    assertArrayEquals(CONTENT, EndpointFile.read(file, 4096));
    Path inherited = Files.write(directory.resolve("inherited.endpoint"), CONTENT);
    assertRefused(inherited, "the endpoint file");
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void anEndpointWithAnEntryForAnotherAccountIsRefused() throws IOException {
    UserPrincipal everyone = group("Everyone");
    Path file = temp.resolve(Endpoint.FILE);
    AtomicFiles.writeOwnerOnly(file, CONTENT);
    assertArrayEquals(CONTENT, EndpointFile.read(file, 4096));
    AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
    List<AclEntry> acl = new ArrayList<AclEntry>(view.getAcl());
    acl.add(
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(everyone)
            .setPermissions(AclEntryPermission.READ_DATA)
            .build());
    view.setAcl(acl);
    assertRefused(file, "open to");
  }

  /** Looks up a group by its English name; the test is skipped where Windows localizes it. */
  private UserPrincipal group(String name) throws IOException {
    try {
      return temp.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByGroupName(name);
    } catch (IOException e) {
      assumeTrue(false, "no group named " + name + ": " + e);
      throw e;
    }
  }
}
