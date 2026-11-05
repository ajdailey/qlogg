package io.github.ajdailey.qlogg.core.time;

/**
 * A line's timestamp.
 *
 * @param offset the line's first byte
 * @param nanos epoch nanoseconds
 * @param fractionDigits second-fraction digits the line had, 0-9
 */
public record Stamp(long offset, long nanos, int fractionDigits) {}
