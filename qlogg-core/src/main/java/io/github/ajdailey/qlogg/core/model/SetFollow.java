package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.rotate.LeftOut;
import io.github.ajdailey.qlogg.core.rotate.LineMap;
import io.github.ajdailey.qlogg.core.rotate.Member;
import io.github.ajdailey.qlogg.core.rotate.MemberNames;
import io.github.ajdailey.qlogg.core.rotate.RotatedSet;
import io.github.ajdailey.qlogg.core.text.EncodingDetection;
import io.github.ajdailey.qlogg.core.watch.ChangeDetector;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import io.github.ajdailey.qlogg.core.watch.FileMonitor;
import io.github.ajdailey.qlogg.core.watch.MonitorListener;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * The watch of a {@link RotatedContent} set. One {@link FileMonitor} watch on the base path, woken
 * by a directory event for any name of the series, checks the last member and, once it is indexed,
 * the member before it. The directory is listed again ({@link RotatedSet#rescan}) when either
 * reports anything but growth of the last member, when a member's reopening read found another
 * file, when the directory's modification time moves, while newer files wait to join, and every
 * {@value #RESCAN_CHECKS} checks. {@link RollOver} decides the response: renamed members are
 * re-pointed and newer files join with line numbers kept ({@code APPENDED}); a lost last member
 * with nothing in its place is {@code MISSING}, once; anything else reports {@code ROLLED} once,
 * with {@link #lineMap}, and stops the checks until {@link #resume}. {@code MISSING} and {@code
 * ROLLED} wait for two rescans in a row to agree, since a listing taken while a writer renames the
 * series can miss a member. Checks run on the watch's thread, one at a time.
 */
final class SetFollow {
  private static final int RESCAN_CHECKS = 10;

  private final RotatedContent content;
  private final SetBytes bytes;
  private final ContentOperations operations;
  private final DocumentServices services;
  private final EncodingDetection detection;
  private final Charset forced;
  private final boolean ignoreCase;
  private final boolean keepClosed;
  private final RollOver rollOver;
  private final AtomicBoolean started = new AtomicBoolean();
  private volatile DocumentListener events;
  private volatile List<LeftOut> leftOut;
  private volatile List<Member> listed;
  private volatile LineMap lineMap;
  private volatile long fileBytes;
  private volatile long modifiedMillis;
  private volatile boolean stale;
  private volatile boolean missing;
  private volatile boolean rescanWanted;
  private int checks;
  private RollOver.Kind unsettled;
  private long directoryModified = Long.MIN_VALUE;

  /**
   * Creates the watch state of a set as discovery found it.
   *
   * @param content the set
   * @param set the discovery
   * @param bytes the set's members and stream
   * @param operations the set's gate and indexing state
   * @param services shared services
   * @param forced the charset the user chose, or null
   * @param ignoreCase true where the file system compares names without regard to case
   * @param keepClosed true to open members only for each read
   */
  SetFollow(
      RotatedContent content,
      RotatedSet set,
      SetBytes bytes,
      ContentOperations operations,
      DocumentServices services,
      Charset forced,
      boolean ignoreCase,
      boolean keepClosed) {
    this.content = content;
    this.bytes = bytes;
    this.operations = operations;
    this.services = services;
    this.detection = set.detection();
    this.forced = forced;
    this.ignoreCase = ignoreCase;
    this.keepClosed = keepClosed;
    this.leftOut = set.leftOut();
    this.listed = set.members();
    this.rollOver =
        new RollOver(
            detection.encoding(),
            services.heldLock(),
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return operations.isClosed();
              }
            });
    this.fileBytes = bytes.layout().last().file().identity().size();
    this.modifiedMillis = bytes.identity().modifiedMillis();
  }

  /**
   * Starts the watch; a second call, or a call after the gate closed, does nothing.
   *
   * @param monitor the file monitor
   * @param listener receives the changes and the indexing events of growth
   */
  void start(FileMonitor monitor, DocumentListener listener) {
    if (operations.isClosed() || !started.compareAndSet(false, true)) {
      return;
    }
    events = listener;
    Path base = bytes.path();
    Path file = base.getFileName();
    final MemberNames names = MemberNames.of(file == null ? "" : file.toString(), ignoreCase);
    try {
      operations.watching(
          monitor.watch(
              base,
              new Predicate<Path>() {
                @Override
                public boolean test(Path name) {
                  return names.match(name.toString()) != null;
                }
              },
              new MonitorListener() {
                @Override
                public void possiblyChanged(Path path) {
                  check();
                }
              }));
    } catch (RejectedExecutionException e) {
      // The workers are closing.
    }
  }

  /** Resumes the checks after the owner's reopen failed. */
  void resume() {
    stale = false;
  }

  boolean stale() {
    return stale;
  }

  boolean missing() {
    return missing;
  }

  long fileBytes() {
    return fileBytes;
  }

  long modifiedMillis() {
    return modifiedMillis;
  }

  LineMap lineMap() {
    return lineMap;
  }

  List<LeftOut> leftOut() {
    return leftOut;
  }

  /** Returns the members of the last listing, with their sizes and modification times. */
  List<Member> listed() {
    return listed;
  }

  private void check() {
    if (stale || !operations.enter()) {
      return;
    }
    try {
      follow();
    } catch (IOException e) {
      // Unreadable for now, for example while a writer renames the series; the next check retries.
      rescanWanted = true;
    } finally {
      operations.exit();
    }
  }

  private void follow() throws IOException {
    SetBytes.Layout layout = bytes.layout();
    List<SetMember> members = layout.members();
    SetMember tail = layout.last();
    boolean rescan = rescanWanted || ++checks % RESCAN_CHECKS == 0 || directoryMoved();
    ChangeDetector.Observation seen = tail.check().classify(tail.path(), covered(tail));
    FileChange change = seen.change();
    rescan |= change != FileChange.UNCHANGED && change != FileChange.APPENDED;
    int before = members.size() - 2;
    if (before >= 0
        && members.get(before).index().snapshot().indexedBytes()
            == layout.rotated().memberEnd(before)) {
      SetMember prior = members.get(before);
      rescan |=
          prior.check().classify(prior.path(), prior.limit()).change() != FileChange.UNCHANGED;
    }
    rescan |= foundOtherFiles(members);
    if (rescan && !rescanned()) {
      return;
    }
    if (change == FileChange.APPENDED || change == FileChange.UNCHANGED) {
      boolean info = seen.size() != fileBytes || seen.modifiedMillis() != modifiedMillis;
      fileBytes = seen.size();
      modifiedMillis = seen.modifiedMillis();
      if (change == FileChange.APPENDED) {
        operations.clearReopenWaiting();
        content.extend();
        events.fileChanged(FileChange.APPENDED);
      } else if (operations.clearReopenWaiting() || info) {
        events.fileChanged(FileChange.UNCHANGED);
      }
    }
  }

  /**
   * Lists the series again and follows the plan. Returns true when the last member's own check
   * still decides what to report.
   */
  private boolean rescanned() throws IOException {
    rescanWanted = false;
    RotatedSet now =
        RotatedSet.rescan(
            bytes.path(),
            services.heldLock(),
            services.fallbackCharset(),
            forced,
            ignoreCase,
            detection);
    leftOut = now.leftOut();
    listed = now.members();
    RollOver.Plan plan = rollOver.plan(bytes.layout().members(), content.indexedMembers(), now);
    switch (plan.kind()) {
      case ROLLED -> {
        if (!settled(plan.kind())) {
          return false;
        }
        lineMap = plan.map();
        stale = true;
        events.fileChanged(FileChange.ROLLED);
        return false;
      }
      case MISSING -> {
        if (!missing && settled(plan.kind())) {
          missing = true;
          events.fileChanged(FileChange.MISSING);
        }
        return false;
      }
      default -> {
        unsettled = null;
        rescanWanted = plan.waiting();
        if (plan.kind() == RollOver.Kind.UNCHANGED && !foundOtherFiles(bytes.layout().members())) {
          missing = false;
          return true;
        }
        if (!apply(plan)) {
          rescanWanted = true;
          return false;
        }
        missing = false;
        operations.clearReopenWaiting();
        content.extend();
        events.fileChanged(FileChange.APPENDED);
        return false;
      }
    }
  }

  /**
   * Reports whether the previous rescan reached the same verdict; otherwise records it and asks for
   * another rescan. A listing taken while a writer renames the series can miss a member.
   */
  private boolean settled(RollOver.Kind kind) {
    if (unsettled == kind) {
      unsettled = null;
      return true;
    }
    unsettled = kind;
    rescanWanted = true;
    return false;
  }

  /**
   * Re-points renamed members and appends joining files, while no indexing pass runs. Returns false
   * when a pass runs; the next check tries again.
   */
  private boolean apply(RollOver.Plan plan) throws IOException {
    if (!operations.holdIndexing()) {
      return false;
    }
    try {
      bytes.follow(plan, services, keepClosed);
      content.followed();
      return true;
    } finally {
      operations.releaseIndexing();
    }
  }

  /** The tail's covered bytes; while indexing runs, at least its size at open. */
  private long covered(SetMember tail) {
    long covered = tail.index().snapshot().indexedBytes();
    if (operations.indexRunning()) {
      covered = Math.max(covered, tail.file().identity().size());
    }
    return covered;
  }

  private static boolean foundOtherFiles(List<SetMember> members) {
    for (SetMember member : members) {
      if (member.foundOtherFile()) {
        return true;
      }
    }
    return false;
  }

  /** True when the directory's modification time moved since the last check. */
  private boolean directoryMoved() {
    Path directory = bytes.path().getParent();
    if (directory == null) {
      return false;
    }
    try {
      long modified = Files.getLastModifiedTime(directory).toMillis();
      boolean moved = directoryModified != Long.MIN_VALUE && modified != directoryModified;
      directoryModified = modified;
      return moved;
    } catch (IOException e) {
      return true;
    }
  }
}
