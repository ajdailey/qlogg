package io.github.ajdailey.qlogg.core.time;

import static io.github.ajdailey.qlogg.core.time.TimeFixture.ASCII;
import static io.github.ajdailey.qlogg.core.time.TimeFixture.nanosAt;
import static io.github.ajdailey.qlogg.core.time.TimeFixture.reader;
import static io.github.ajdailey.qlogg.core.time.TimeFixture.stampAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.io.MemoryByteSource;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class TimeReaderTest {
  private static final String TRACE = "\tat mock.Frame.run(Frame.java:12)\n";
  private final CancellationToken token = new CancellationToken();

  @Test
  void atWalksBackThroughAStackTrace() throws Exception {
    String text = stampAt(0) + " INFO a\n" + stampAt(5) + " ERROR b\n" + TRACE + TRACE;
    TimeReader reader = reader(source(text), ASCII);
    long last = text.length() - TRACE.length();
    long second = text.indexOf(stampAt(5));
    assertEquals(new Stamp(second, nanosAt(5), 3), reader.at(last, token));
    assertEquals(new Stamp(second, nanosAt(5), 3), reader.at(second, token));
    assertEquals(new Stamp(0, nanosAt(0), 3), reader.at(0, token));
  }

  @Test
  void atStopsAfterTenThousandLines() throws Exception {
    int lines = TimeReader.MAX_LINES - 1;
    String within = stampAt(0) + "\n" + "c\n".repeat(lines);
    assertEquals(nanosAt(0), reader(source(within), ASCII).at(within.length() - 2, token).nanos());
    String beyond = stampAt(0) + "\n" + "c\n".repeat(lines + 1);
    assertNull(reader(source(beyond), ASCII).at(beyond.length() - 2, token));
    assertNull(reader(source("a\nb\nc\n"), ASCII).at(4, token), "no stamp above");
  }

  @Test
  void atStopsAfterOneMebibyte() throws Exception {
    String text = stampAt(0) + "\n" + "c".repeat(TimeReader.MAX_BYTES) + "\nd\n";
    assertNull(reader(source(text), ASCII).at(text.length() - 2, token));
    String shorter = stampAt(0) + "\n" + "c".repeat(TimeReader.MAX_BYTES - 64) + "\nd\n";
    assertEquals(
        nanosAt(0), reader(source(shorter), ASCII).at(shorter.length() - 2, token).nanos());
  }

  @Test
  void nextFromAMidLineOffsetTakesTheFollowingLine() throws Exception {
    String text = stampAt(0) + " a\n" + TRACE + stampAt(7) + " b\n";
    TimeReader reader = reader(source(text), ASCII);
    long third = text.indexOf(stampAt(7));
    assertEquals(new Stamp(0, nanosAt(0), 3), reader.next(0, text.length(), token));
    assertEquals(new Stamp(third, nanosAt(7), 3), reader.next(3, text.length(), token));
    assertEquals(new Stamp(third, nanosAt(7), 3), reader.next(third, text.length(), token));
    assertEquals(
        new Stamp(third, nanosAt(7), 3), reader.next(text.indexOf(TRACE), text.length(), token));
    assertNull(reader.next(third + 1, text.length(), token));
    assertNull(reader.next(text.length(), text.length(), token), "at end of file");
    assertNull(reader.next(0, 0, token), "empty region");
  }

  @Test
  void aFinalLineWithoutLineFeed() throws Exception {
    String text = TRACE + stampAt(9) + " last";
    TimeReader reader = reader(source(text), ASCII);
    long last = TRACE.length();
    assertEquals(new Stamp(last, nanosAt(9), 3), reader.next(1, text.length(), token));
    assertEquals(new Stamp(last, nanosAt(9), 3), reader.at(last, token));
  }

  @Test
  void nextStopsAfterTenThousandLines() throws Exception {
    String text = "c\n".repeat(TimeReader.MAX_LINES) + stampAt(1) + "\n";
    assertNull(reader(source(text), ASCII).next(0, text.length(), token));
    String within = "c\n".repeat(TimeReader.MAX_LINES - 1) + stampAt(1) + "\n";
    assertEquals(nanosAt(1), reader(source(within), ASCII).next(0, within.length(), token).nanos());
  }

  @Test
  void longLinesSpanSeveralBuffers() throws Exception {
    String text = stampAt(0) + "x".repeat(200_000) + "\n" + "y".repeat(150_000) + "\n" + stampAt(3);
    TimeReader reader = reader(source(text), ASCII);
    long last = text.indexOf(stampAt(3));
    assertEquals(new Stamp(last, nanosAt(3), 3), reader.next(10, text.length(), token));
    assertEquals(0, reader.at(text.indexOf('y'), token).offset());
  }

  @Test
  void crlfAndUtf16() throws Exception {
    String text = "\r\n" + stampAt(4) + "\r\n" + TRACE;
    byte[] body = text.getBytes(StandardCharsets.UTF_16LE);
    byte[] bytes = new byte[body.length + 2];
    bytes[0] = (byte) 0xff;
    bytes[1] = (byte) 0xfe;
    System.arraycopy(body, 0, bytes, 2, body.length);
    TimeReader reader =
        reader(new MemoryByteSource(bytes, 0), TextEncoding.of(StandardCharsets.UTF_16LE, 2));
    assertEquals(new Stamp(6, nanosAt(4), 3), reader.next(0, bytes.length, token));
    assertEquals(new Stamp(6, nanosAt(4), 3), reader.next(3, bytes.length, token));
    assertEquals(new Stamp(6, nanosAt(4), 3), reader.at(bytes.length - 2 * TRACE.length(), token));
  }

  @Test
  void aCanceledTokenStopsALookup() throws Exception {
    final TimeReader reader = reader(source("c\n".repeat(100)), ASCII);
    token.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            reader.next(0, 200, token);
          }
        });
  }

  private static MemoryByteSource source(String text) {
    return new MemoryByteSource(text.getBytes(StandardCharsets.US_ASCII), 0);
  }
}
