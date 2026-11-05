package io.github.ajdailey.qlogg.core.time;

import static io.github.ajdailey.qlogg.core.time.TimeFixture.ASCII;
import static io.github.ajdailey.qlogg.core.time.TimeFixture.UTC;
import static io.github.ajdailey.qlogg.core.time.TimeFixture.stampAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FormatDetectorTest {
  @Test
  void stackTracesAtEightyPercentStillDetect() {
    StringBuilder head = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      head.append(stampAt(i)).append(" ERROR mock failed\n");
      for (int j = 0; j < 4; j++) {
        head.append("\tat mock.Frame.run(Frame.java:").append(j).append(")\n");
      }
    }
    assertEquals(BuiltInFormat.ISO, detect(head.toString()));
  }

  @Test
  void belowTwentyPercentOrThreeLinesDetectsNothing() {
    StringBuilder head = new StringBuilder();
    for (int i = 0; i < 39; i++) {
      head.append(stampAt(i)).append(" ERROR mock\n");
      for (int j = 0; j < 4; j++) {
        head.append("\tat mock.Frame.run\n");
      }
    }
    head.append("\tat mock.Frame.run\n");
    assertNull(detect(head.toString()), "39 of 196");
    assertNull(detect(stampAt(0) + " a\n" + stampAt(1) + " b\n"), "two lines");
    assertEquals(BuiltInFormat.ISO, detect(stampAt(0) + "\n\n\n" + stampAt(1) + "\n" + stampAt(2)));
  }

  @Test
  void dayFirstNeedsAFirstFieldOverTwelve() {
    StringBuilder head = new StringBuilder();
    for (int i = 1; i <= 12; i++) {
      head.append(String.format("%02d/03/2026 05:00:00 mock", i)).append('\n');
    }
    assertEquals(BuiltInFormat.MDY_SLASH, detect(head.toString()));
    head.append("13/03/2026 05:00:00 mock\n");
    assertEquals(BuiltInFormat.DMY_SLASH, detect(head.toString()));
  }

  @Test
  void noFormat() {
    StringBuilder head = new StringBuilder();
    for (int i = 0; i < 50; i++) {
      head.append("mock entry ").append(i).append('\n');
    }
    assertNull(detect(head.toString()));
    assertNull(detect(""));
  }

  @Test
  void tiesGoToTableOrder() {
    StringBuilder head = new StringBuilder();
    for (int i = 0; i < 10; i++) {
      head.append("2026-03-01 05:00:00 mock\n").append("2026/03/01 05:00:00 mock\n");
    }
    assertEquals(BuiltInFormat.ISO, detect(head.toString()));
  }

  @Test
  void onlyTheFirstTwoHundredNonEmptyLinesCount() {
    StringBuilder head = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      head.append("mock entry\n");
    }
    for (int i = 0; i < 100; i++) {
      head.append(stampAt(i)).append('\n');
    }
    assertNull(detect(head.toString()));
  }

  @Test
  void utf16LittleEndianHeadAfterItsBom() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 5; i++) {
      text.append(stampAt(i)).append(" mock\r\n");
    }
    byte[] body = text.toString().getBytes(StandardCharsets.UTF_16LE);
    ByteBuffer head = ByteBuffer.allocate(body.length + 2);
    head.put((byte) 0xff).put((byte) 0xfe).put(body).flip();
    TextEncoding encoding = TextEncoding.of(StandardCharsets.UTF_16LE, 2);
    assertEquals(BuiltInFormat.ISO, FormatDetector.detect(head, encoding, UTC));
    assertEquals(0, head.position(), "head not consumed");
  }

  private static BuiltInFormat detect(String text) {
    return FormatDetector.detect(
        ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII)), ASCII, UTC);
  }
}
