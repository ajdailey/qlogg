package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.index.GrowableLineIndex;
import io.github.ajdailey.qlogg.core.index.IndexCapacityException;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.FileByteSource;
import io.github.ajdailey.qlogg.core.io.FileIdentity;
import io.github.ajdailey.qlogg.core.io.ReopeningByteSource;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import io.github.ajdailey.qlogg.core.rotate.MemberIdentity;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * One file of a {@link RotatedContent} set: its current name, the open file, the line index over it
 * and the digests a check and a rescan compare. As a {@link ByteSource} it reads the open file
 * under the member's current name, up to its limit, and {@link #repoint} moves it to another open
 * file of the same content after a roll-over renamed or copied it. Thread-safe; the owner
 * serializes {@link #repoint}, {@link #limit} and {@link #rename} with indexing.
 */
final class SetMember implements ByteSource {
  private final int bomLength;
  private final GrowableLineIndex index;
  private volatile Path path;
  private volatile ByteSource file;
  private volatile FileCheck check;
  private volatile long limit = Long.MAX_VALUE;

  private SetMember(
      Path path,
      ByteSource file,
      int bomLength,
      TextEncoding set,
      MemoryBudget budget,
      BlockCache cache)
      throws IndexCapacityException {
    this.path = path;
    this.file = file;
    this.check = new FileCheck(file);
    this.bomLength = bomLength;
    TextEncoding own = new TextEncoding(set.charset(), set.unitWidth(), set.order(), bomLength);
    this.index = new GrowableLineIndex(own, budget, this, cache);
  }

  /**
   * Opens a member's file and creates its empty index. Blocking.
   *
   * @param path the file
   * @param bomLength its byte order mark length
   * @param set the set's encoding
   * @param services shared services
   * @param keepClosed true to open the file only for each read
   * @return the member; the caller closes it
   * @throws IOException when the file cannot be opened or the index share refuses an empty index
   */
  static SetMember open(
      Path path, int bomLength, TextEncoding set, DocumentServices services, boolean keepClosed)
      throws IOException {
    ByteSource file = openFile(path, services.heldLock(), keepClosed);
    boolean opened = false;
    try {
      SetMember member =
          new SetMember(path, file, bomLength, set, services.budget(), services.cache());
      opened = true;
      return member;
    } finally {
      if (!opened) {
        file.close();
      }
    }
  }

  /**
   * Opens a file as a member reads it.
   *
   * @param path the file
   * @param held the instance lock, or null
   * @param keepClosed true for a {@link ReopeningByteSource}
   * @return the open file; the caller closes it
   * @throws IOException when the path is rejected or the file cannot be opened
   */
  static ByteSource openFile(Path path, SafePaths.HeldLock held, boolean keepClosed)
      throws IOException {
    FileByteSource opened = FileByteSource.open(path, held);
    if (!keepClosed) {
      return opened;
    }
    try {
      return ReopeningByteSource.of(opened);
    } finally {
      opened.close();
    }
  }

  /** Returns the member's current name. */
  @Override
  public Path path() {
    return path;
  }

  /** Returns the file's size, at most the limit. */
  @Override
  public long size() throws IOException {
    return Math.min(file.size(), limit);
  }

  @Override
  public int read(long position, ByteBuffer dst) throws IOException {
    return file.read(position, dst);
  }

  @Override
  public FileIdentity identity() {
    return file.identity();
  }

  /** Closes the open file. */
  @Override
  public void close() throws IOException {
    file.close();
  }

  int bomLength() {
    return bomLength;
  }

  GrowableLineIndex index() {
    return index;
  }

  ByteSource file() {
    return file;
  }

  FileCheck check() {
    return check;
  }

  long limit() {
    return limit;
  }

  /**
   * Fixes the size the member shows, when it stops being the set's last member.
   *
   * @param bytes the size
   */
  void limit(long bytes) {
    limit = bytes;
  }

  /**
   * Records a new name for the same open file, as after a rename that a held file follows.
   *
   * @param next the new name
   */
  void rename(Path next) {
    path = next;
  }

  /**
   * Moves the member to another open file that holds its indexed bytes, under a new name, and
   * records the digests at the indexed end. The previous file is returned, not closed.
   *
   * @param next the new name
   * @param opened the open file
   * @return the previous file
   */
  ByteSource repoint(Path next, ByteSource opened) {
    ByteSource previous = file;
    FileCheck fresh = new FileCheck(opened);
    fresh.record(next, index.snapshot().indexedBytes());
    path = next;
    file = opened;
    check = fresh;
    return previous;
  }

  /** Records the digests at the indexed end, after an indexing pass. */
  void record() {
    check.record(path, index.snapshot().indexedBytes());
  }

  /**
   * Reports whether the member holds the file at a path open, compared by file key. Blocking.
   *
   * @param other the path
   * @return false for a reopening member, on a system without file keys, or when the path cannot be
   *     read
   */
  boolean holds(Path other) {
    Object key = file.identity().fileKey();
    if (!(file instanceof FileByteSource) || key == null) {
      return false;
    }
    try {
      return key.equals(Files.readAttributes(other, BasicFileAttributes.class).fileKey());
    } catch (IOException e) {
      return false;
    }
  }

  /** True when the member's reopening reads found another file at its real path. */
  boolean foundOtherFile() {
    return file instanceof ReopeningByteSource reopening && reopening.changed();
  }

  /** Closes a source no read uses any more, ignoring a failure. */
  static void closeQuietly(ByteSource source) {
    try {
      source.close();
    } catch (IOException e) {
      // Nothing reads it any more.
    }
  }

  /**
   * Returns the identity a rescan finds the member by.
   *
   * @return the identity, or null when no digests are recorded
   */
  MemberIdentity memberIdentity() {
    return check.identity();
  }
}
