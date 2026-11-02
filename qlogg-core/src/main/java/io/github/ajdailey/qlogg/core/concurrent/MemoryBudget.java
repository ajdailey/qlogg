package io.github.ajdailey.qlogg.core.concurrent;

import io.github.ajdailey.qlogg.core.util.Checks;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Splits a maximum heap size into {@link MemoryShare}s that all open documents draw from. A
 * structure reserves before it grows and releases on close; a refused reservation makes the
 * structure degrade (a sparse line index, a capped result set) instead of failing with an
 * out-of-memory error. The 8% assigned to no share is headroom. Thread-safe.
 */
public final class MemoryBudget {
  private final long maxBytes;
  private final long[] limits;
  private final AtomicLong[] used;

  private MemoryBudget(long maxBytes) {
    Checks.notNegative(maxBytes, "maxBytes");
    this.maxBytes = maxBytes;
    MemoryShare[] shares = MemoryShare.values();
    this.limits = new long[shares.length];
    this.used = new AtomicLong[shares.length];
    for (MemoryShare share : shares) {
      limits[share.ordinal()] = maxBytes / 100 * share.percent();
      used[share.ordinal()] = new AtomicLong();
    }
  }

  /**
   * Creates the budget from {@code Runtime.maxMemory()}.
   *
   * @return the budget
   */
  public static MemoryBudget fromRuntime() {
    return new MemoryBudget(Runtime.getRuntime().maxMemory());
  }

  /**
   * Creates a budget from a fixed maximum heap size.
   *
   * @param maxBytes the size the shares divide
   * @return the budget
   */
  public static MemoryBudget of(long maxBytes) {
    return new MemoryBudget(maxBytes);
  }

  /**
   * Returns the size the shares divide.
   *
   * @return bytes
   */
  public long maxBytes() {
    return maxBytes;
  }

  /**
   * Returns the size of a share.
   *
   * @param share the share
   * @return bytes
   */
  public long limit(MemoryShare share) {
    return limits[share.ordinal()];
  }

  /**
   * Reserves bytes from the share.
   *
   * @param share the share
   * @param bytes at least 0
   * @return false, reserving nothing, when the share cannot hold them
   */
  public boolean reserve(MemoryShare share, long bytes) {
    Checks.notNegative(bytes, "bytes");
    AtomicLong counter = used[share.ordinal()];
    long limit = limits[share.ordinal()];
    while (true) {
      long current = counter.get();
      if (bytes > limit - current) {
        return false;
      }
      if (counter.compareAndSet(current, current + bytes)) {
        return true;
      }
    }
  }

  /**
   * Reserves bytes without the limit check, for the minimum a structure needs to work at all. The
   * share can exceed its limit afterwards, which refuses later reservations until it is released.
   *
   * @param share the share
   * @param bytes at least 0
   */
  public void charge(MemoryShare share, long bytes) {
    Checks.notNegative(bytes, "bytes");
    used[share.ordinal()].addAndGet(bytes);
  }

  /**
   * Returns bytes reserved or charged earlier.
   *
   * @param share the share
   * @param bytes at most what the caller holds
   */
  public void release(MemoryShare share, long bytes) {
    Checks.notNegative(bytes, "bytes");
    used[share.ordinal()].addAndGet(-bytes);
  }

  /**
   * Returns what the share can still hold.
   *
   * @param share the share
   * @return bytes, 0 when full or overdrawn
   */
  public long available(MemoryShare share) {
    return Math.max(0, limits[share.ordinal()] - used[share.ordinal()].get());
  }

  /**
   * Returns what the share holds.
   *
   * @param share the share
   * @return bytes reserved and charged, minus bytes released
   */
  public long reserved(MemoryShare share) {
    return used[share.ordinal()].get();
  }
}
