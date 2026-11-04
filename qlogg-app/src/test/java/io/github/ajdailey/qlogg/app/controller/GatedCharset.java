package io.github.ajdailey.qlogg.app.controller;

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ISO-8859-1 under another name, for the fallback charset of test document services. Encoding
 * detection falls back to it for a file that is not valid UTF-8, and checks {@link #canEncode}
 * inside {@code LogDocument.open} after the file is open. Once {@link #arm}ed, the next such check
 * blocks until {@link #release}.
 */
final class GatedCharset extends Charset {
  private final AtomicBoolean armed = new AtomicBoolean();
  private final AtomicInteger checks = new AtomicInteger();
  private final CountDownLatch reached = new CountDownLatch(1);
  private final CountDownLatch released = new CountDownLatch(1);

  GatedCharset() {
    super("X-QLOGG-TEST-GATE", null);
  }

  /** Makes the next open of a non-UTF-8 file wait for {@link #release}. */
  void arm() {
    armed.set(true);
  }

  /** Waits until an armed open is held. */
  boolean awaitHeld() throws InterruptedException {
    return reached.await(20, TimeUnit.SECONDS);
  }

  /** Lets the held open continue. */
  void release() {
    released.countDown();
  }

  /** Counts the opens that fell back to this charset. */
  int opens() {
    return checks.get();
  }

  @Override
  public boolean canEncode() {
    checks.incrementAndGet();
    if (armed.compareAndSet(true, false)) {
      reached.countDown();
      try {
        released.await(20, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    return true;
  }

  @Override
  public boolean contains(Charset other) {
    return StandardCharsets.ISO_8859_1.contains(other);
  }

  @Override
  public CharsetDecoder newDecoder() {
    return StandardCharsets.ISO_8859_1.newDecoder();
  }

  @Override
  public CharsetEncoder newEncoder() {
    return StandardCharsets.ISO_8859_1.newEncoder();
  }
}
