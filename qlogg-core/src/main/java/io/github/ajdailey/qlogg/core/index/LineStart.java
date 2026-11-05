package io.github.ajdailey.qlogg.core.index;

/**
 * A line and its first byte.
 *
 * @param line the line number
 * @param offset the line's first byte
 */
public record LineStart(long line, long offset) {}
