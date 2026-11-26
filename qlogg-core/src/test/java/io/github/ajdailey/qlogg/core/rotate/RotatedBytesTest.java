package io.github.ajdailey.qlogg.core.rotate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.index.GrowableLineIndex;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.FileByteSource;
import io.github.ajdailey.qlogg.core.io.FileChangedException;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class RotatedBytesTest {
  private static final TextEncoding UTF8 = TextEncoding.of(StandardCharsets.UTF_8, 0);
  private static final TextEncoding UTF16LE = TextEncoding.of(StandardCharsets.UTF_16LE, 0);
  private static final byte[] UTF8_BOM = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
  private static final byte[] UTF16LE_BOM = {(byte) 0xff, (byte) 0xfe};

  @TempDir Path dir;

  private RotateFixture fixture;

  @BeforeEach
  void start() {
    fixture = new RotateFixture();
  }

  @AfterEach
  void stop() {
    fixture.close();
  }

  @Test
  void marksAreLeftOutAndAMissingLineFeedIsAdded() throws Exception {
    try (RotatedBytes bytes =
        open(
            UTF8,
            new byte[][] {
              join(UTF8_BOM, ascii("one\ntwo")), ascii(""), join(UTF8_BOM, ascii("x\n"))
            },
            new int[] {3, 0, 3})) {
      assertArrayEquals(ascii("one\ntwo\nx\n"), RotatedOracleTest.read(bytes, 0, 10));
      assertEquals(10, bytes.size());
      assertEquals(1, bytes.addedBytes(0));
      assertEquals(0, bytes.addedBytes(1));
      assertEquals(0, bytes.addedBytes(2));
      assertEquals(8, bytes.memberStart(1));
      assertEquals(8, bytes.memberStart(2));
      assertEquals(10, bytes.memberEnd(0));
      assertEquals(Long.MAX_VALUE, bytes.memberEnd(2));
      assertEquals(dir.resolve("app.log"), bytes.path());
    }
  }

  @Test
  void aUtf16MemberGetsATwoByteLineFeedAndLosesAPartialUnit() throws Exception {
    byte[] first = join(UTF16LE_BOM, utf16("a\nb"), new byte[] {'z'});
    byte[] second = join(UTF16LE_BOM, utf16("c"));
    try (RotatedBytes bytes = open(UTF16LE, new byte[][] {first, second}, new int[] {2, 2})) {
      assertArrayEquals(utf16("a\nb\nc"), RotatedOracleTest.read(bytes, 0, 10));
      assertEquals(10, bytes.size());
      assertEquals(2, bytes.addedBytes(0));
      assertEquals(8, bytes.memberEnd(0));
      assertEquals(8, bytes.member(0).size());
      assertEquals(-1, bytes.member(0).read(8, ByteBuffer.allocate(4)));
    }
  }

  @Test
  void aReadStopsAtEachBoundary() throws Exception {
    try (RotatedBytes bytes =
        open(UTF8, new byte[][] {ascii("ab"), ascii("cd\n"), ascii("ef")}, new int[3])) {
      ByteBuffer buffer = ByteBuffer.allocate(16);
      assertEquals(2, bytes.read(0, buffer));
      assertEquals(1, bytes.read(2, buffer));
      assertEquals(3, bytes.read(3, buffer));
      assertEquals(2, bytes.read(6, buffer));
      assertEquals(-1, bytes.read(8, buffer));
      buffer.flip();
      assertArrayEquals(ascii("ab\ncd\nef"), toArray(buffer));
      ByteBuffer one = ByteBuffer.allocate(1);
      assertEquals(1, bytes.read(1, one));
      assertEquals('b', one.get(0));
      assertEquals(0, bytes.read(1, one));
    }
  }

  @Test
  void theLastMemberGrowsAndItsIndexExtends() throws Exception {
    Path tail = dir.resolve("app.log");
    try (RotatedBytes bytes =
        open(UTF8, new byte[][] {ascii("old\n"), ascii("new pa")}, new int[2])) {
      GrowableLineIndex first = fixture.index(bytes.member(0), UTF8, RotateFixture.LARGE);
      GrowableLineIndex last = fixture.index(bytes.member(1), UTF8, RotateFixture.LARGE);
      try {
        RotatedIndex before = RotatedIndex.of(bytes, List.of(first.snapshot(), last.snapshot()));
        assertEquals(2, before.lineCount());
        assertFalse(before.lastLineComplete());
        Files.write(tail, ascii("rt\nmore\n"), StandardOpenOption.APPEND);
        assertEquals(18, bytes.size());
        fixture.extend(bytes.member(1), last);
        RotatedIndex after = RotatedIndex.of(bytes, List.of(first.snapshot(), last.snapshot()));
        assertEquals(3, after.lineCount());
        assertTrue(after.lastLineComplete());
        assertEquals(4, after.lineStart(1));
        assertEquals(12, after.lineEnd(1));
        assertEquals(1, after.memberOf(2));
        assertEquals(1, after.memberLine(2));
        assertEquals(0, after.memberOf(0));
        assertTrue(after.generation() > before.generation());
      } finally {
        first.close();
        last.close();
      }
    }
  }

  @Test
  void anIncompleteMemberBeforeTheLastIsRefused() throws Exception {
    final RotatedBytes bytes = open(UTF8, new byte[][] {ascii("a\nb\n"), ascii("c\n")}, new int[2]);
    try (bytes;
        GrowableLineIndex first =
            new GrowableLineIndex(UTF8, RotateFixture.LARGE, bytes.member(0), fixture.cache)) {
      final List<LineIndex> snapshots = new ArrayList<LineIndex>();
      snapshots.add(first.snapshot());
      snapshots.add(first.snapshot());
      assertThrows(
          IllegalArgumentException.class,
          new Executable() {
            @Override
            public void execute() {
              RotatedIndex.of(bytes, snapshots);
            }
          });
      RotatedIndex prefix = RotatedIndex.of(bytes, snapshots.subList(0, 1));
      assertEquals(0, prefix.lineCount());
      assertEquals(1, prefix.indexedMembers());
    }
  }

  @Test
  void aShrunkMemberBeforeTheLastFailsTheRead() throws Exception {
    final RotatedBytes bytes =
        open(UTF8, new byte[][] {ascii("first line\n"), ascii("x\n")}, new int[2]);
    try (bytes) {
      try (FileChannel channel =
          FileChannel.open(dir.resolve("app.log.1"), StandardOpenOption.WRITE)) {
        channel.truncate(3);
      }
      assertThrows(
          FileChangedException.class,
          new Executable() {
            @Override
            public void execute() throws IOException {
              bytes.read(5, ByteBuffer.allocate(4));
            }
          });
    }
  }

  /** Writes members {@code app.log.N} to {@code app.log}, oldest first, and opens them. */
  private RotatedBytes open(TextEncoding encoding, byte[][] files, int[] boms) throws IOException {
    List<RotatedBytes.Part> parts = new ArrayList<RotatedBytes.Part>();
    for (int i = 0; i < files.length; i++) {
      int age = files.length - 1 - i;
      Path file = dir.resolve(age == 0 ? "app.log" : "app.log." + age);
      Files.write(file, files[i]);
      parts.add(new RotatedBytes.Part(FileByteSource.open(file, null), boms[i]));
    }
    return RotatedBytes.open(dir.resolve("app.log"), encoding, parts);
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] utf16(String text) {
    return text.getBytes(StandardCharsets.UTF_16LE);
  }

  private static byte[] toArray(ByteBuffer buffer) {
    byte[] out = new byte[buffer.remaining()];
    buffer.get(out);
    return out;
  }

  private static byte[] join(byte[]... pieces) {
    int length = 0;
    for (byte[] piece : pieces) {
      length += piece.length;
    }
    ByteBuffer out = ByteBuffer.allocate(length);
    for (byte[] piece : pieces) {
      out.put(piece);
    }
    return out.array();
  }
}
