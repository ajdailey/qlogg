package io.github.ajdailey.qlogg.core.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class AtomicFilesTest {
  @TempDir Path temp;

  private boolean has(String view) {
    return temp.getFileSystem().supportedFileAttributeViews().contains(view);
  }

  private static String mode(Path file) throws IOException {
    return PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

  private static String read(Path file) throws IOException {
    return Files.readString(file, StandardCharsets.US_ASCII);
  }

  /** Creates a symbolic link, or skips the test where the user cannot create one. */
  private static Path symbolicLink(Path link, Path target) throws IOException {
    try {
      return Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | FileSystemException e) {
      assumeTrue(false, "no symbolic links here: " + e);
      return link;
    }
  }

  private List<String> names() throws IOException {
    List<String> names = new ArrayList<String>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(temp)) {
      for (Path entry : entries) {
        names.add(entry.getFileName().toString());
      }
    }
    return names;
  }

  /** A move refused with access denied until {@code refusals} calls have failed. */
  private static AtomicFiles.Move refused(final int refusals, final AtomicInteger calls) {
    return new AtomicFiles.Move() {
      @Override
      public void run() throws IOException {
        if (calls.incrementAndGet() <= refusals) {
          throw new AccessDeniedException("target");
        }
      }
    };
  }

  /** A move failing every time with the given exception. */
  private static AtomicFiles.Move failing(final IOException failure, final AtomicInteger calls) {
    return new AtomicFiles.Move() {
      @Override
      public void run() throws IOException {
        calls.incrementAndGet();
        throw failure;
      }
    };
  }

  @Test
  void aRefusedMoveIsTriedAgain() throws IOException {
    AtomicInteger calls = new AtomicInteger();
    AtomicFiles.tryMove(refused(2, calls), 3);
    assertEquals(3, calls.get());
  }

  @Test
  void aSharingViolationIsTriedAgain() throws IOException {
    final AtomicInteger calls = new AtomicInteger();
    AtomicFiles.tryMove(
        new AtomicFiles.Move() {
          @Override
          public void run() throws IOException {
            if (calls.incrementAndGet() == 1) {
              throw new FileSystemException("target", null, "used by another process");
            }
          }
        },
        2);
    assertEquals(2, calls.get());
  }

  @Test
  void aMoveRefusedEveryTimeThrowsAfterTheLastAttempt() {
    final AtomicInteger calls = new AtomicInteger();
    assertThrows(
        AccessDeniedException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            AtomicFiles.tryMove(refused(5, calls), 3);
          }
        });
    assertEquals(3, calls.get());
  }

  @Test
  void everyFileSystemExceptionIsTriedAgainAndOtherFailuresAreNot() {
    final AtomicInteger missing = new AtomicInteger();
    assertThrows(
        NoSuchFileException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            AtomicFiles.tryMove(failing(new NoSuchFileException("temp"), missing), 3);
          }
        });
    assertEquals(3, missing.get());
    final AtomicInteger disk = new AtomicInteger();
    assertThrows(
        IOException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            AtomicFiles.tryMove(failing(new IOException("disk"), disk), 3);
          }
        });
    assertEquals(1, disk.get());
  }

  @Test
  void writesAndReplacesLeavingNoTemporaryFile() throws IOException {
    Path file = temp.resolve("qlogg.conf");
    AtomicFiles.write(file, "first".getBytes(StandardCharsets.US_ASCII));
    AtomicFiles.write(file, "second".getBytes(StandardCharsets.US_ASCII));
    assertArrayEquals("second".getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(file));
    assertEquals(List.of("qlogg.conf"), names());
    if (temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }
  }

  @Test
  void aFailedMoveKeepsTheTargetAndRemovesTheTemporaryFile() throws IOException {
    final Path dir = Files.createDirectory(temp.resolve("target-is-a-directory"));
    Files.writeString(dir.resolve("child"), "x");
    assertThrows(
        IOException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            AtomicFiles.write(dir, new byte[] {1});
          }
        });
    assertEquals(List.of("target-is-a-directory"), names());
  }

  @Test
  void writesAStreamAndAFailedReadKeepsTheTarget() throws IOException {
    final Path file = temp.resolve("guide.html");
    AtomicFiles.write(file, new ByteArrayInputStream("page".getBytes(StandardCharsets.US_ASCII)));
    assertEquals("page", Files.readString(file, StandardCharsets.US_ASCII));
    assertThrows(
        IOException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            AtomicFiles.write(
                file,
                new InputStream() {
                  @Override
                  public int read() throws IOException {
                    throw new IOException("mock read failure");
                  }
                });
          }
        });
    assertEquals("page", Files.readString(file, StandardCharsets.US_ASCII));
    assertEquals(List.of("guide.html"), names());
  }

  /** The Windows CI account is elevated, so the file's owner there is not the user. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void aSecretHasOneAclEntryForTheCurrentUser() throws IOException {
    Path file = temp.resolve("instance.endpoint");
    AtomicFiles.writeOwnerOnly(file, "token".getBytes(StandardCharsets.US_ASCII));
    AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
    List<AclEntry> acl = view.getAcl();
    assertEquals(1, acl.size());
    assertEquals(AclEntryType.ALLOW, acl.get(0).type());
    assertEquals(EnumSet.allOf(AclEntryPermission.class), acl.get(0).permissions());
    String expected = System.getenv("USERDOMAIN") + "\\" + System.getProperty("user.name");
    assertEquals(
        expected.toLowerCase(Locale.ROOT),
        acl.get(0).principal().getName().toLowerCase(Locale.ROOT));
    assertEquals("token", Files.readString(file, StandardCharsets.US_ASCII));
  }

  @Test
  void aUserFileKeepsTheModeOfTheFileItReplaces() throws IOException {
    assumeTrue(has("posix"));
    Path file = Files.writeString(temp.resolve("filters.conf"), "old");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
    AtomicFiles.writeUserFile(file, ascii("new"));
    assertEquals("new", read(file));
    assertEquals("rw-r-----", mode(file));
    assertEquals(List.of("filters.conf"), names());
  }

  @Test
  void aNewUserFileTakesTheModeOfAnyNewFile() throws IOException {
    assumeTrue(has("posix"));
    Path plain = Files.createFile(temp.resolve("plain.txt"));
    Path file = temp.resolve("saved.txt");
    AtomicFiles.writeUserFile(file, ascii("new"));
    assertEquals("new", read(file));
    assertEquals(mode(plain), mode(file));
  }

  @Test
  void aSymbolicLinkStaysALinkAndTheFileItNamesIsReplaced() throws IOException {
    Path target = Files.writeString(temp.resolve("real.conf"), "old");
    Path link = symbolicLink(temp.resolve("link.conf"), target.getFileName());
    AtomicFiles.writeUserFile(link, ascii("user"));
    assertTrue(Files.isSymbolicLink(link));
    assertEquals("user", read(target));
    AtomicFiles.write(link, ascii("own"));
    assertTrue(Files.isSymbolicLink(link));
    assertEquals("own", read(target));
    Path dangling = symbolicLink(temp.resolve("dangling.conf"), Path.of("made.conf"));
    AtomicFiles.writeUserFile(dangling, ascii("new"));
    assertTrue(Files.isSymbolicLink(dangling));
    assertEquals("new", read(temp.resolve("made.conf")));
  }

  @Test
  void aHardLinkedUserFileIsWrittenInPlace() throws IOException {
    assumeTrue(has("unix"));
    Path file = Files.writeString(temp.resolve("a.conf"), "old");
    Path other = Files.createLink(temp.resolve("b.conf"), file);
    AtomicFiles.writeUserFile(file, ascii("new"));
    assertEquals("new", read(other));
  }

  @Test
  void aUserFileInAFolderThatIsNotWritableIsWrittenInPlace() throws IOException {
    assumeTrue(has("posix"));
    Path dir = Files.createDirectory(temp.resolve("locked"));
    Path file = Files.writeString(dir.resolve("saved.txt"), "old");
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"));
    try {
      assumeFalse(Files.isWritable(dir), "the folder stays writable, as for root");
      AtomicFiles.writeUserFile(file, ascii("new"));
      assertEquals("new", read(file));
    } finally {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  void aUserFileThatIsNotWritableIsRefusedUnchanged() throws IOException {
    final Path file = Files.writeString(temp.resolve("locked.txt"), "old");
    if (has("posix")) {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
    } else {
      Files.setAttribute(file, "dos:readonly", Boolean.TRUE);
    }
    try {
      assumeFalse(Files.isWritable(file), "the file stays writable, as for root");
      assertThrows(
          AccessDeniedException.class,
          new Executable() {
            @Override
            public void execute() throws IOException {
              AtomicFiles.writeUserFile(file, ascii("new"));
            }
          });
      assertEquals("old", read(file));
      assertEquals(List.of("locked.txt"), names());
    } finally {
      if (has("posix")) {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
      } else {
        Files.setAttribute(file, "dos:readonly", Boolean.FALSE);
      }
    }
  }
}
