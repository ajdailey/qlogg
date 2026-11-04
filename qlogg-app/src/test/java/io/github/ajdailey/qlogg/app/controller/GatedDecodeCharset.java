package io.github.ajdailey.qlogg.app.controller;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * ISO-8859-1 under another name, for the fallback charset of test document services. Each decode of
 * a line starting with {@code G} waits for a permit ({@link #pass}) until {@link #open}, so a copy
 * over such lines stops at a known row.
 */
final class GatedDecodeCharset extends Charset {
  /** Longest wait for a permit; past it the decode goes on. */
  private static final long WAIT_SECONDS = 60;

  private final Semaphore permits = new Semaphore(0);
  private final CountDownLatch reached = new CountDownLatch(1);
  private volatile boolean open;

  GatedDecodeCharset() {
    super("X-QLOGG-TEST-DECODE-GATE", null);
  }

  /** Waits until a decode of a gated line is held. */
  boolean awaitHeld() throws InterruptedException {
    return reached.await(20, TimeUnit.SECONDS);
  }

  /** Lets one held or later gated decode continue. */
  void pass() {
    permits.release();
  }

  /** Lets every gated decode continue from now on. */
  void open() {
    open = true;
    permits.release(Integer.MAX_VALUE / 2);
  }

  @Override
  public boolean contains(Charset other) {
    return StandardCharsets.ISO_8859_1.contains(other);
  }

  @Override
  public CharsetDecoder newDecoder() {
    return new CharsetDecoder(this, 1f, 1f) {
      private boolean lineStart = true;

      @Override
      protected void implReset() {
        lineStart = true;
      }

      @Override
      protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) {
        if (lineStart && in.hasRemaining() && in.get(in.position()) == 'G' && !open) {
          reached.countDown();
          try {
            permits.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
        lineStart = false;
        while (in.hasRemaining()) {
          if (!out.hasRemaining()) {
            return CoderResult.OVERFLOW;
          }
          out.put((char) (in.get() & 0xFF));
        }
        return CoderResult.UNDERFLOW;
      }
    };
  }

  @Override
  public CharsetEncoder newEncoder() {
    return StandardCharsets.ISO_8859_1.newEncoder();
  }
}
