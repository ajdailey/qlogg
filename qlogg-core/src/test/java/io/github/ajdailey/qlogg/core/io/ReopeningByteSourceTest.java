package io.github.ajdailey.qlogg.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** {@link ReopeningByteSource}: no file held between reads, and every read verifies the file. */
class ReopeningByteSourceTest {
  @TempDir Path dir;

  private static ReopeningByteSource open(Path file) throws IOException {
    try (FileByteSource opened = FileByteSource.open(file, null)) {
      return ReopeningByteSource.of(opened);
    }
  }

  private static String read(ByteSource source, long position, int length) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(length);
    int count = source.read(position, buffer);
    return count < 0 ? null : new String(buffer.array(), 0, count, StandardCharsets.US_ASCII);
  }

  private static Executable reading(final ByteSource source) {
    return new Executable() {
      @Override
      public void execute() throws Throwable {
        read(source, 0, 4);
      }
    };
  }

  private static long crc(byte[] bytes) {
    CRC32C crc = new CRC32C();
    crc.update(bytes);
    return crc.getValue();
  }

  @Test
  void readsAppendedBytesAndReportsTheCurrentSize() throws Exception {
    Path file = Files.writeString(dir.resolve("app.log"), "first\n");
    try (ReopeningByteSource source = open(file)) {
      assertEquals(file.toAbsolutePath().normalize(), source.path());
      assertEquals(file.toRealPath(), source.identity().realPath());
      Files.writeString(file, "second\n", StandardOpenOption.APPEND);
      assertEquals(13, source.size());
      assertEquals("second", read(source, 6, 6));
      assertEquals(null, read(source, 13, 4));
      assertFalse(source.changed());
    }
  }

  /** On Windows the rename is refused while any handle is open; nothing holds one here. */
  @Test
  void aRenameOverThePathSucceedsAndTheNextReadFails() throws Exception {
    Path file = Files.writeString(dir.resolve("app.log"), "old content\n");
    Path next = Files.writeString(dir.resolve("app.log.next"), "new content\n");
    try (ReopeningByteSource source = open(file)) {
      assertEquals("old ", read(source, 0, 4));
      Files.move(next, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      assertThrows(FileChangedException.class, reading(source));
      assertTrue(source.changed());
    }
  }

  @Test
  void aRewriteOfTheHeadFailsTheRead() throws Exception {
    Path file = Files.writeString(dir.resolve("app.log"), "first\n");
    try (ReopeningByteSource source = open(file)) {
      Files.writeString(file, "FIRST\n");
      assertThrows(FileChangedException.class, reading(source));
      assertTrue(source.changed());
    }
  }

  /** The same head with other bytes at the indexed end: old offsets must not read the new file. */
  @Test
  void aRewriteOfTheExpectedTailFailsTheRead() throws Exception {
    byte[] head = "head\n".getBytes(StandardCharsets.US_ASCII);
    byte[] tail = "tail\n".getBytes(StandardCharsets.US_ASCII);
    Path file = Files.writeString(dir.resolve("app.log"), "head\ntail\n");
    try (ReopeningByteSource source = open(file)) {
      source.expect(head.length, crc(head), 10, tail.length, crc(tail));
      assertEquals("head", read(source, 0, 4));
      Files.writeString(file, "head\nTAIL\n");
      assertThrows(FileChangedException.class, reading(source));
    }
  }

  @Test
  void aTruncationBelowTheExpectedTailFailsTheRead() throws Exception {
    byte[] head = "head\n".getBytes(StandardCharsets.US_ASCII);
    byte[] tail = "tail\n".getBytes(StandardCharsets.US_ASCII);
    Path file = Files.writeString(dir.resolve("app.log"), "head\ntail\n");
    try (ReopeningByteSource source = open(file)) {
      source.expect(head.length, crc(head), 10, tail.length, crc(tail));
      Files.writeString(file, "head\n");
      assertThrows(FileChangedException.class, reading(source));
    }
  }

  @Test
  void aDeletedFileFailsAsMissingAndItsReplacementAsChanged() throws Exception {
    Path file = Files.writeString(dir.resolve("app.log"), "first\n");
    try (ReopeningByteSource source = open(file)) {
      Files.delete(file);
      assertThrows(NoSuchFileException.class, reading(source));
      assertFalse(source.changed());
      Files.writeString(file, "other\n");
      assertThrows(FileChangedException.class, reading(source));
      assertTrue(source.changed());
    }
  }

  @Test
  void aReadAfterCloseThrowsClosedChannel() throws Exception {
    Path file = Files.writeString(dir.resolve("app.log"), "first\n");
    final ReopeningByteSource source = open(file);
    source.close();
    assertThrows(ClosedChannelException.class, reading(source));
    assertThrows(
        ClosedChannelException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            source.size();
          }
        });
  }

  private Path linkTo(Path target, String name) throws IOException {
    try {
      return Files.createSymbolicLink(dir.resolve(name), target);
    } catch (FileSystemException e) {
      assumeTrue(
          File.separatorChar == '/',
          "creating a symbolic link on Windows needs SeCreateSymbolicLinkPrivilege");
      throw e;
    }
  }

  /** The source stays on the real path it opened; the watch reports the retarget. */
  @Test
  void aRetargetedLinkKeepsReadingTheFileOpened() throws Exception {
    Path first = Files.writeString(dir.resolve("first.log"), "first\n");
    Path second = Files.writeString(dir.resolve("second.log"), "other\n");
    Path link = linkTo(first, "app.log");
    try (ReopeningByteSource source = open(link)) {
      assertEquals(link.toAbsolutePath().normalize(), source.path());
      Files.delete(link);
      linkTo(second, "app.log");
      assertEquals("firs", read(source, 0, 4));
      assertFalse(source.changed());
    }
  }

  /** A link put at the real path is not followed to another file. */
  @Test
  void aLinkAtTheRealPathFailsTheRead() throws Exception {
    Path file = Files.writeString(dir.resolve("app.log"), "first\n");
    Path other = Files.writeString(dir.resolve("other.log"), "first\n");
    try (ReopeningByteSource source = open(file)) {
      Files.delete(file);
      linkTo(other, "app.log");
      assertThrows(FileChangedException.class, reading(source));
    }
  }
}
