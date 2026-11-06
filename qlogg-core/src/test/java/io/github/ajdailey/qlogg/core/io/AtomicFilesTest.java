package io.github.ajdailey.qlogg.core.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class AtomicFilesTest {
  @TempDir Path temp;

  private List<String> names() throws IOException {
    List<String> names = new ArrayList<String>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(temp)) {
      for (Path entry : entries) {
        names.add(entry.getFileName().toString());
      }
    }
    return names;
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
}
