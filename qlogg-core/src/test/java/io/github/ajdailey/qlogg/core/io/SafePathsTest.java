package io.github.ajdailey.qlogg.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class SafePathsTest {
  @TempDir Path temp;

  @AfterEach
  void release() {
    SafePaths.releaseLock();
  }

  @Test
  void rejectsTheHeldLockFileUntilItIsReleased() throws Exception {
    final Path lock = Files.createFile(temp.resolve("instance.lock"));
    SafePaths.holdLock(lock, "mock refusal");
    IOException refused =
        assertThrows(
            IOException.class,
            new Executable() {
              @Override
              public void execute() throws IOException {
                SafePaths.logPath(lock);
              }
            });
    assertEquals("mock refusal", refused.getMessage());
    SafePaths.releaseLock();
    assertEquals(lock.toRealPath(), SafePaths.logFile(lock));
  }

  @Test
  void rejectsALinkToTheHeldLockFile() throws Exception {
    Path lock = Files.createFile(temp.resolve("instance.lock"));
    assumeTrue(Files.readAttributes(lock, BasicFileAttributes.class).fileKey() != null);
    final Path hard = Files.createLink(temp.resolve("hard"), lock);
    final Path soft = Files.createSymbolicLink(temp.resolve("soft"), lock);
    SafePaths.holdLock(lock, "mock refusal");
    for (final Path link : List.of(hard, soft)) {
      assertThrows(
          IOException.class,
          new Executable() {
            @Override
            public void execute() throws IOException {
              SafePaths.logFile(link);
            }
          });
    }
  }

  @Test
  void acceptsOtherFilesWhileALockIsHeld() throws Exception {
    Path other = Files.createFile(temp.resolve("mock.log"));
    SafePaths.holdLock(Files.createFile(temp.resolve("instance.lock")), "mock refusal");
    assertEquals(other.toRealPath(), SafePaths.logFile(other));
  }

  private static void rejects(final String path) {
    assertThrows(
        IOException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            SafePaths.logFile(path);
          }
        },
        path);
  }

  @Test
  void existingFileResolvesToRealPath() throws Exception {
    Path file = Files.createFile(temp.resolve("app.log"));
    Path dotted = temp.resolve("sub").resolve("..").resolve("app.log");
    assertEquals(file.toRealPath(), SafePaths.logFile(dotted.toString()));
  }

  @Test
  void aSymbolicLinkIsKeptAsGivenAndResolvedForTheRealPath() throws Exception {
    assumeTrue(File.separatorChar == '/', "symbolic links need privileges on Windows");
    Path target = Files.createFile(temp.resolve("app-1.log"));
    Path link = Files.createSymbolicLink(temp.resolve("app.log"), target);
    SafePaths.LogPath checked = SafePaths.logPath(link);
    assertEquals(link.toAbsolutePath().normalize(), checked.given());
    assertEquals(target.toRealPath(), checked.real());
    Path dir = Files.createDirectory(temp.resolve("dir"));
    Files.delete(link);
    Files.createSymbolicLink(link, dir);
    rejects(link.toString());
  }

  @Test
  void missingFileNeedsParentDirectory() throws Exception {
    assertEquals(
        temp.toRealPath().resolve("later.log"),
        SafePaths.logFile(temp.resolve("later.log").toString()));
    rejects(temp.resolve("nodir").resolve("x.log").toString());
  }

  @Test
  void rejectsDirectoryNulAndEmpty() {
    rejects(temp.toString());
    rejects("a\0b");
    rejects("");
  }

  @Test
  void rejectsFifo() throws Exception {
    assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("posix"), "no POSIX");
    Path fifo = temp.resolve("pipe");
    Process mkfifo;
    try {
      mkfifo = new ProcessBuilder("mkfifo", fifo.toString()).inheritIO().start();
    } catch (IOException e) {
      assumeTrue(false, "no mkfifo");
      return;
    }
    assumeTrue(mkfifo.waitFor() == 0 && Files.exists(fifo), "mkfifo failed");
    rejects(fifo.toString());
  }

  @Test
  void rejectsDevice() {
    Path device = Path.of("/dev/null");
    assumeTrue(Files.exists(device), "no /dev/null");
    rejects(device.toString());
  }

  @Test
  void windowsDeviceForms() {
    for (String path :
        List.of(
            "CON",
            "nul.txt",
            "C:\\logs\\COM1",
            "\\\\.\\PhysicalDrive0",
            "\\\\?\\GLOBALROOT\\x",
            "lpt9.log")) {
      assertTrue(SafePaths.windowsDevice(path), path);
    }
    for (String path : List.of("C:\\logs\\console.log", "context", "COM10", "app.log")) {
      assertFalse(SafePaths.windowsDevice(path), path);
    }
  }

  @Test
  void settingsDirectoryIsOwnerOnly() throws Exception {
    Path dir = SafePaths.settingsDirectory(temp.resolve("a").resolve("settings"));
    assertTrue(Files.isDirectory(dir));
    assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
  }

  @Test
  void childStaysInside() throws Exception {
    assertEquals(temp.resolve("qlogg.conf"), SafePaths.child(temp, "qlogg.conf"));
    for (final String name : List.of("..", "../x", "a/b", "", ".")) {
      assertThrows(
          IOException.class,
          new Executable() {
            @Override
            public void execute() throws Throwable {
              SafePaths.child(temp, name);
            }
          },
          name);
    }
  }
}
