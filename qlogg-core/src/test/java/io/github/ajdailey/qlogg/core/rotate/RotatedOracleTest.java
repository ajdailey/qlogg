package io.github.ajdailey.qlogg.core.rotate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.index.GrowableLineIndex;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.index.LineStart;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.FileByteSource;
import io.github.ajdailey.qlogg.core.search.LineSource;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchLimit;
import io.github.ajdailey.qlogg.core.search.SearchListener;
import io.github.ajdailey.qlogg.core.search.SearchQuery;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Random rotated sets against one file holding their normalized concatenation: no byte order marks,
 * an LF after each member before the last that lacks one, a trailing partial UTF-16 unit cut.
 */
class RotatedOracleTest {
  private static final int ROUNDS = 25;
  private static final String ALPHABET = "abcxyz09 \t-";
  private static final String[] QUERIES = {"ab", "[xz]9", "^$", "é"};

  @TempDir Path dir;

  private RotateFixture fixture;
  private int sparseMembers;

  @BeforeEach
  void start() {
    fixture = new RotateFixture();
  }

  @AfterEach
  void stop() {
    fixture.close();
  }

  @ParameterizedTest
  @ValueSource(strings = {"UTF-8", "UTF-16LE", "UTF-16BE"})
  void randomSetsMatchTheirConcatenation(String name) throws Exception {
    Charset charset = Charset.forName(name);
    Random random = new Random(name.hashCode());
    for (int round = 0; round < ROUNDS; round++) {
      Path folder = Files.createDirectory(dir.resolve("round" + round));
      int count = 1 + random.nextInt(6);
      List<byte[]> files = new ArrayList<byte[]>();
      List<Integer> boms = new ArrayList<Integer>();
      List<Boolean> sparse = new ArrayList<Boolean>();
      for (int i = 0; i < count; i++) {
        byte[] bom = random.nextBoolean() ? bom(charset) : new byte[0];
        sparse.add(random.nextInt(3) == 0);
        byte[] text = text(random, charset, sparse.get(i) ? 130 + random.nextInt(200) : 30);
        boolean oddByte =
            i < count - 1 && !charset.equals(StandardCharsets.UTF_8) && random.nextInt(8) == 0;
        files.add(join(bom, text, oddByte ? new byte[] {'q'} : new byte[0]));
        boms.add(bom.length);
      }
      check(folder, TextEncoding.of(charset, 0), files, boms, sparse, random);
    }
    assertTrue(sparseMembers > 0, "no member was sparse");
  }

  private void check(
      Path folder,
      TextEncoding set,
      List<byte[]> files,
      List<Integer> boms,
      List<Boolean> sparse,
      Random random)
      throws Exception {
    int count = files.size();
    List<RotatedBytes.Part> parts = new ArrayList<RotatedBytes.Part>();
    for (int i = 0; i < count; i++) {
      Path file = folder.resolve("app.log." + (count - i));
      Files.write(file, files.get(i));
      parts.add(new RotatedBytes.Part(FileByteSource.open(file, null), boms.get(i)));
    }
    List<GrowableLineIndex> indexes = new ArrayList<GrowableLineIndex>();
    try (RotatedBytes bytes = RotatedBytes.open(folder.resolve("app.log"), set, parts)) {
      List<LineIndex> snapshots = new ArrayList<LineIndex>();
      for (int i = 0; i < count; i++) {
        MemoryBudget budget = sparse.get(i) ? RotateFixture.sparse() : RotateFixture.LARGE;
        TextEncoding own =
            new TextEncoding(set.charset(), set.unitWidth(), set.order(), boms.get(i));
        GrowableLineIndex index = fixture.index(bytes.member(i), own, budget);
        indexes.add(index);
        snapshots.add(index.snapshot());
        if (index.snapshot().sparse()) {
          sparseMembers++;
        }
      }
      byte[] whole = concatenation(set, files, boms, count);
      assertEquals(whole.length, bytes.size(), "size");
      compareStream(bytes, whole, random);
      compareWithOracle(folder, "all", set, bytes, RotatedIndex.of(bytes, snapshots), whole);
      int prefix = random.nextInt(count);
      if (prefix > 0) {
        compareWithOracle(
            folder,
            "prefix",
            set,
            bytes,
            RotatedIndex.of(bytes, snapshots.subList(0, prefix)),
            concatenation(set, files, boms, prefix));
      }
    } finally {
      for (GrowableLineIndex index : indexes) {
        index.close();
      }
    }
  }

  private void compareWithOracle(
      Path folder,
      String name,
      TextEncoding set,
      RotatedBytes bytes,
      RotatedIndex index,
      byte[] whole)
      throws Exception {
    Path file = folder.resolve(name + ".log");
    Files.write(file, whole);
    try (FileByteSource source = FileByteSource.open(file, null);
        GrowableLineIndex oracle = fixture.index(source, set, RotateFixture.LARGE)) {
      LineIndex expected = oracle.snapshot();
      compareIndexes(expected, index);
      for (long line = 0; line < expected.lineCount(); line++) {
        long start = expected.lineStart(line);
        int length = (int) (expected.lineEnd(line) - start);
        assertArrayEquals(
            Arrays.copyOfRange(whole, (int) start, (int) start + length),
            read(bytes, start, length),
            "line " + line);
      }
      SearchEngine engine =
          new SearchEngine(
              fixture.workers,
              StandardMatchers.INSTANCE,
              new SearchSettings(3, 64, 4, 0, RotateFixture.LARGE));
      LineSource rotated = RotateFixture.lines(index, bytes, set);
      LineSource single = RotateFixture.lines(expected, source, set);
      for (String query : QUERIES) {
        assertEquals(search(engine, single, query), search(engine, rotated, query), query);
      }
    }
  }

  static void compareIndexes(LineIndex expected, LineIndex actual) {
    long count = expected.lineCount();
    assertEquals(count, actual.lineCount(), "lineCount");
    assertEquals(expected.indexedBytes(), actual.indexedBytes(), "indexedBytes");
    assertEquals(expected.lastLineComplete(), actual.lastLineComplete(), "lastLineComplete");
    assertEquals(expected.maxLineBytes(), actual.maxLineBytes(), "maxLineBytes");
    for (long line = 0; line < count; line++) {
      assertEquals(expected.lineStart(line), actual.lineStart(line), "lineStart " + line);
      assertEquals(expected.lineEnd(line), actual.lineEnd(line), "lineEnd " + line);
      LineStart nearest = actual.nearestStart(line);
      assertTrue(nearest.line() <= line, "nearestStart " + line);
      assertEquals(expected.lineStart(nearest.line()), nearest.offset(), "nearestStart " + line);
    }
    for (long offset = -1; offset <= expected.indexedBytes() + 2; offset++) {
      assertEquals(expected.lineAt(offset), actual.lineAt(offset), "lineAt " + offset);
    }
  }

  private static List<Long> search(SearchEngine engine, LineSource source, String pattern)
      throws InterruptedException {
    MutableLineSet results = new MutableLineSet();
    final OperationOutcome[] outcome = new OperationOutcome[1];
    OperationHandle handle =
        engine.search(
            source,
            SearchQuery.of(pattern, MatchMode.REGEX),
            results,
            new SearchListener() {
              @Override
              public void progressed(long searched, long total, long matches) {
                // Only the outcome matters.
              }

              @Override
              public void finished(
                  OperationOutcome result, SearchLimit limit, long skipped, Exception failure) {
                outcome[0] = result;
              }
            });
    assertTrue(handle.await(RotateFixture.WAIT_MILLIS));
    assertEquals(OperationOutcome.COMPLETED, outcome[0], pattern);
    List<Long> lines = new ArrayList<Long>();
    for (long i = 0; i < results.size(); i++) {
      lines.add(results.select(i));
    }
    return lines;
  }

  /** Reads the whole stream from random positions in random lengths. */
  private static void compareStream(RotatedBytes bytes, byte[] whole, Random random)
      throws Exception {
    assertArrayEquals(whole, read(bytes, 0, whole.length), "whole stream");
    for (int i = 0; i < 50 && whole.length > 0; i++) {
      int from = random.nextInt(whole.length);
      int length = random.nextInt(whole.length - from + 1);
      assertArrayEquals(
          Arrays.copyOfRange(whole, from, from + length),
          read(bytes, from, length),
          "read at " + from);
    }
    assertEquals(-1, bytes.read(whole.length, ByteBuffer.allocate(4)), "end of stream");
  }

  static byte[] read(ByteSource source, long position, int length) throws Exception {
    ByteBuffer buffer = ByteBuffer.allocate(length);
    while (buffer.hasRemaining()) {
      int read = source.read(position + buffer.position(), buffer);
      assertTrue(read > 0, "read stopped at " + (position + buffer.position()));
    }
    return buffer.array();
  }

  /** The first members' bytes as one file: marks left out, LFs added, partial units cut. */
  private static byte[] concatenation(
      TextEncoding set, List<byte[]> files, List<Integer> boms, int members) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] lineFeed = set.lineFeed();
    int unit = lineFeed.length;
    int total = files.size();
    for (int i = 0; i < members; i++) {
      byte[] file = files.get(i);
      int bom = boms.get(i);
      int length = file.length - bom;
      if (i < total - 1) {
        length = length / unit * unit;
      }
      out.write(file, bom, length);
      boolean endsWithLf =
          length >= unit
              && Arrays.equals(file, bom + length - unit, bom + length, lineFeed, 0, unit);
      if (i < total - 1 && length > 0 && !endsWithLf) {
        out.write(lineFeed, 0, unit);
      }
    }
    return out.toByteArray();
  }

  private static byte[] text(Random random, Charset charset, int mostLines) {
    if (random.nextInt(8) == 0) {
      return new byte[0];
    }
    StringBuilder text = new StringBuilder();
    int lines = random.nextInt(mostLines);
    for (int line = 0; line < lines; line++) {
      int length = random.nextInt(20) == 0 ? 300 : random.nextInt(40);
      for (int i = 0; i < length; i++) {
        text.append(random.nextInt(30) == 0 ? 'é' : ALPHABET.charAt(random.nextInt(11)));
      }
      if (random.nextInt(10) == 0) {
        text.append('\r');
      }
      text.append('\n');
    }
    if (random.nextBoolean()) {
      text.append("partial ab");
    }
    return text.toString().getBytes(charset);
  }

  private static byte[] bom(Charset charset) {
    if (charset.equals(StandardCharsets.UTF_16LE)) {
      return new byte[] {(byte) 0xff, (byte) 0xfe};
    }
    if (charset.equals(StandardCharsets.UTF_16BE)) {
      return new byte[] {(byte) 0xfe, (byte) 0xff};
    }
    return new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
  }

  private static byte[] join(byte[]... pieces) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] piece : pieces) {
      out.write(piece, 0, piece.length);
    }
    return out.toByteArray();
  }
}
