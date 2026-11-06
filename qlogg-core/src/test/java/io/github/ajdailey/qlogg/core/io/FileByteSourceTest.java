package io.github.ajdailey.qlogg.core.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class FileByteSourceTest {
  @TempDir Path dir;

  @Test
  void positionalReadsAndIdentity() throws Exception {
    byte[] content = new byte[10_000];
    for (int i = 0; i < content.length; i++) {
      content[i] = (byte) ('a' + i % 26);
    }
    Path file = dir.resolve("a.log");
    Files.write(file, content);
    try (FileByteSource source = FileByteSource.open(file)) {
      assertEquals(file.toAbsolutePath().normalize(), source.path());
      assertEquals(file.toRealPath(), source.realPath());
      assertEquals(10_000, source.size());
      ByteBuffer middle = ByteBuffer.allocate(5);
      assertEquals(5, source.read(7000, middle));
      assertArrayEquals(Arrays.copyOfRange(content, 7000, 7005), middle.array());
      assertEquals(-1, source.read(10_000, ByteBuffer.allocate(1)));
      FileIdentity identity = source.identity();
      assertEquals(file.toRealPath(), identity.realPath());
      CRC32C crc = new CRC32C();
      crc.update(content, 0, FileIdentity.HEAD_BYTES);
      assertEquals(crc.getValue(), identity.headDigest());
      assertEquals(10_000, identity.size());
    }
    Files.delete(file);
  }

  @Test
  void readAfterCloseThrowsClosedChannel() throws Exception {
    Path file = dir.resolve("b.log");
    Files.write(file, "x\n".getBytes(StandardCharsets.US_ASCII));
    final FileByteSource source = FileByteSource.open(file);
    source.close();
    assertFalse(source.isOpen());
    assertThrows(
        ClosedChannelException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            source.read(0, ByteBuffer.allocate(1));
          }
        });
    Files.delete(file);
  }

  @Test
  void rejectsDirectoryAndMissingFile() {
    assertThrows(
        IOException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            FileByteSource.open(dir).close();
          }
        });
    assertThrows(
        NoSuchFileException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            FileByteSource.open(dir.resolve("missing.log")).close();
          }
        });
  }
}
