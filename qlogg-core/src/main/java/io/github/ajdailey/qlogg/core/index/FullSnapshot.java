package io.github.ajdailey.qlogg.core.index;

/** Snapshot over packed storage; every lookup is in memory. */
final class FullSnapshot extends IndexSnapshot {
  private final PackedStarts.View view;

  FullSnapshot(
      PackedStarts.View view,
      long firstStart,
      long lastStart,
      long indexedBytes,
      long maxLineBytes,
      long generation,
      int unit) {
    super(firstStart, view.count(), lastStart, indexedBytes, maxLineBytes, generation, unit);
    this.view = view;
  }

  @Override
  long startOf(long line) {
    return view.start(line);
  }

  @Override
  long lineContaining(long offset) {
    return view.lineAt(offset);
  }

  @Override
  public boolean sparse() {
    return false;
  }
}
