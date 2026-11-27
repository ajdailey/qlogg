package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.index.IndexListener;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.FileIdentity;
import io.github.ajdailey.qlogg.core.io.SafePaths;
import io.github.ajdailey.qlogg.core.rotate.LeftOut;
import io.github.ajdailey.qlogg.core.rotate.LineMap;
import io.github.ajdailey.qlogg.core.rotate.Member;
import io.github.ajdailey.qlogg.core.rotate.RotatedIndex;
import io.github.ajdailey.qlogg.core.rotate.RotatedSet;
import io.github.ajdailey.qlogg.core.rotate.SetFiles;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.SortedLines;
import io.github.ajdailey.qlogg.core.text.EncodingDetection;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import io.github.ajdailey.qlogg.core.watch.FileMonitor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

/**
 * A rotated set behind a {@link LogDocument}: the files {@link RotatedSet#discover} found, read as
 * one {@link SetBytes} stream with one {@link RotatedIndex}. The document shows the base name;
 * {@link SetFollow} follows the set through roll-over.
 *
 * <p>Indexing: one member at a time, oldest first, through the shared indexer, as one operation
 * toward the listener. The published index holds every completed member plus the growing prefix of
 * the one being indexed, so line numbers below it never change during the load. {@link
 * #stopIndexing} cancels the running member and leaves later members unindexed.
 */
final class RotatedContent implements DocumentContent {
  private final Path base;
  private final Path realBase;
  private final EncodingDetection detection;
  private final BuiltInFormat timeFormat;
  private final ByteBuffer head;
  private final ColumnSchema columns;
  private final DocumentServices services;
  private final boolean keepClosed;
  private final SetBytes bytes;
  private final SetFollow follow;
  private final ContentOperations operations = new ContentOperations();
  private volatile DocumentListener events;
  private volatile RotatedIndex published;
  private volatile long totalBytes;

  private RotatedContent(
      RotatedSet set,
      SetBytes bytes,
      BuiltInFormat timeFormat,
      ByteBuffer head,
      ColumnSchema columns,
      DocumentServices services,
      boolean keepClosed,
      boolean ignoreCase,
      Charset forced)
      throws IOException {
    this.base = set.base();
    this.realBase = SafePaths.logPath(base, services.heldLock()).real();
    this.detection = set.detection();
    this.timeFormat = timeFormat;
    this.head = head.asReadOnlyBuffer();
    this.columns = columns;
    this.services = services;
    this.keepClosed = keepClosed;
    this.bytes = bytes;
    this.follow =
        new SetFollow(this, set, bytes, operations, services, forced, ignoreCase, keepClosed);
    this.totalBytes = bytes.size();
    publish();
  }

  /**
   * Discovers and opens the set a file belongs to, as {@link RotatedSet#discover} lists it, and
   * detects the column format and, when asked, the timestamp format from the newest member with
   * bytes. Blocking.
   *
   * @param path any member's path, or the base path
   * @param forcedEncoding the charset to use, or null to detect it
   * @param services shared services
   * @param detectTimeFormat false when the owner has a remembered format
   * @param keepClosed true to open each member only for each read
   * @param ignoreCase true where the file system compares names without regard to case
   * @return the content; the caller closes it
   * @throws IOException when the path is rejected, the series has no readable member, a member
   *     cannot be opened, or the line index share cannot hold an empty index per member
   */
  static RotatedContent open(
      Path path,
      Charset forcedEncoding,
      DocumentServices services,
      boolean detectTimeFormat,
      boolean keepClosed,
      boolean ignoreCase)
      throws IOException {
    RotatedSet set =
        RotatedSet.discover(
            path, services.heldLock(), services.fallbackCharset(), forcedEncoding, ignoreCase);
    if (set.members().isEmpty()) {
      throw new IOException(set.base() + " has no readable file in its rotation series");
    }
    List<SetMember> members = new ArrayList<SetMember>();
    boolean opened = false;
    try {
      BuiltInFormat timeFormat = null;
      for (int i = 0; i < set.members().size(); i++) {
        Member member = set.members().get(i);
        SetMember open =
            SetMember.open(
                member.path(), member.encoding().bomLength(), set.encoding(), services, keepClosed);
        members.add(open);
        if (i < set.members().size() - 1) {
          open.limit(open.file().size());
        }
      }
      ByteBuffer head = ByteBuffer.allocate(0);
      ColumnSchema columns = null;
      for (int i = members.size() - 1; i >= 0; i--) {
        Member member = set.members().get(i);
        if (member.size() > 0) {
          head = SingleFileContent.readHead(members.get(i));
          EncodingDetection detection =
              new EncodingDetection(member.encoding(), set.detection().reason());
          FileIdentity identity = members.get(i).identity();
          if (detectTimeFormat) {
            timeFormat = SingleFileContent.detectTimeFormat(head, detection, identity);
          }
          Path name = set.base().getFileName();
          columns =
              SingleFileContent.detectColumns(
                  head, detection, name == null ? "" : name.toString(), identity);
          break;
        }
      }
      SetBytes stream = SetBytes.of(set.base(), set.encoding(), members);
      RotatedContent content =
          new RotatedContent(
              set,
              stream,
              timeFormat,
              head,
              columns,
              services,
              keepClosed,
              ignoreCase,
              forcedEncoding);
      opened = true;
      return content;
    } finally {
      if (!opened) {
        for (SetMember member : members) {
          member.index().close();
          SetMember.closeQuietly(member);
        }
      }
    }
  }

  /**
   * Returns the map of the last {@code ROLLED} report.
   *
   * @return the map, or null before one
   */
  @Override
  public LineMap lineMap() {
    return follow.lineMap();
  }

  /**
   * Returns the files of the series the set does not load, as the last listing found them.
   *
   * @return the files and reasons
   */
  List<LeftOut> leftOut() {
    return follow.leftOut();
  }

  @Override
  public SetFiles files() {
    Map<Path, Member> listed = new HashMap<Path, Member>();
    for (Member member : follow.listed()) {
      listed.put(member.path(), member);
    }
    List<SetFiles.Loaded> loaded = new ArrayList<SetFiles.Loaded>();
    for (SetMember member : bytes.layout().members()) {
      FileIdentity identity = member.identity();
      Member found = listed.get(member.path());
      loaded.add(
          new SetFiles.Loaded(
              member.path(),
              found == null ? identity.size() : found.size(),
              found == null ? identity.modifiedMillis() : found.modifiedMillis(),
              identity.headDigest()));
    }
    return new SetFiles(loaded, follow.leftOut());
  }

  @Override
  public long[] memberBases(BooleanSupplier closing) throws IOException {
    SetBytes.Layout layout = bytes.layout();
    int count = layout.members().size();
    long[] bases = new long[count];
    for (int i = 0; i + 1 < count; i++) {
      SetMember member = layout.members().get(i);
      LineCounter.Counted counted =
          LineCounter.count(
              layout.rotated().member(i),
              member.path(),
              member.bomLength(),
              layout.rotated().memberEnd(i),
              detection.encoding(),
              closing);
      // A member before the last that does not end with LF is followed by one.
      bases[i + 1] = bases[i] + counted.lines();
    }
    return bases;
  }

  @Override
  public String name() {
    Path file = base.getFileName();
    return file == null ? base.toString() : file.toString();
  }

  @Override
  public Path path() {
    return base;
  }

  @Override
  public Path realPath() {
    return realBase;
  }

  @Override
  public boolean inMemory() {
    return false;
  }

  @Override
  public boolean keepsFileClosed() {
    return keepClosed;
  }

  @Override
  public EncodingDetection detection() {
    return detection;
  }

  @Override
  public BuiltInFormat timeFormat() {
    return timeFormat;
  }

  @Override
  public ByteBuffer head() {
    return head.duplicate();
  }

  @Override
  public ColumnSchema columns() {
    return columns;
  }

  @Override
  public LineSet memberStarts(LineIndex index) {
    return index instanceof RotatedIndex rotated ? new SortedLines(rotated.memberStarts()) : null;
  }

  @Override
  public ByteSource bytes() {
    return bytes;
  }

  @Override
  public LineIndex index() {
    return published;
  }

  @Override
  public DocumentStatus status(boolean closed) {
    RotatedIndex snapshot = published;
    boolean running = operations.indexRunning();
    long total = Math.max(totalBytes, snapshot.indexedBytes());
    int percent = 100;
    if (running) {
      percent = total > 0 ? (int) Math.min(100, snapshot.indexedBytes() * 100 / total) : 0;
    }
    return new DocumentStatus(
        snapshot.lineCount(),
        total,
        percent,
        running,
        snapshot.sparse(),
        closed,
        follow.fileBytes(),
        follow.modifiedMillis(),
        follow.missing(),
        operations.loadingStopped(),
        operations.reopenWaiting());
  }

  @Override
  public boolean enter() {
    return operations.enter();
  }

  @Override
  public void exit() {
    operations.exit();
  }

  @Override
  public void startIndexing(DocumentListener listener) {
    events = listener;
    operations.startIndexing(pass);
  }

  @Override
  public boolean stopIndexing() {
    return operations.stopIndexing();
  }

  @Override
  public void startWatching(FileMonitor monitor, DocumentListener listener) {
    follow.start(monitor, listener);
  }

  @Override
  public boolean reopenFailed() {
    boolean first = operations.reopenFailed();
    follow.resume();
    return first;
  }

  /**
   * Re-publishes the index and measures the set after members were re-pointed or joined, and
   * indexes what they hold.
   */
  void followed() throws IOException {
    publish();
    totalBytes = bytes.size();
  }

  /** Indexes growth now, or when the running pass ends. */
  void extend() {
    operations.extend(pass, follow.stale());
  }

  /**
   * Returns the members in the published index.
   *
   * @return members from member 0
   */
  int indexedMembers() {
    return published.indexedMembers();
  }

  @Override
  public boolean reopenWaiting() {
    return operations.reopenWaiting();
  }

  /** Starts the pass of the first member not indexed to its end, or of the last member. */
  private final ContentOperations.Pass pass =
      new ContentOperations.Pass() {
        @Override
        public void start() {
          indexNext();
        }
      };

  private void indexNext() {
    SetBytes.Layout layout = bytes.layout();
    int member = cursor(layout);
    SetMember target = layout.members().get(member);
    operations.indexing(
        services
            .indexer()
            .index(
                layout.rotated().member(member),
                target.index(),
                target.index().resumeOffset(),
                new MemberEvents(member, layout)));
  }

  /** The first member before the last that is not indexed to its end, else the last. */
  private static int cursor(SetBytes.Layout layout) {
    int last = layout.members().size() - 1;
    for (int i = 0; i < last; i++) {
      long indexed = layout.members().get(i).index().snapshot().indexedBytes();
      if (indexed != layout.rotated().memberEnd(i)) {
        return i;
      }
    }
    return last;
  }

  /** Publishes the completed members and the growing prefix of the cursor's member. */
  private synchronized void publish() {
    SetBytes.Layout layout = bytes.layout();
    int cursor = cursor(layout);
    List<LineIndex> snapshots = new ArrayList<LineIndex>();
    for (int i = 0; i <= cursor; i++) {
      snapshots.add(layout.members().get(i).index().snapshot());
    }
    published = RotatedIndex.of(layout.rotated(), snapshots);
  }

  /** One member pass's events; the chain of passes holds one gate entry until its end. */
  private final class MemberEvents implements IndexListener {
    private final int member;
    private final SetBytes.Layout layout;

    MemberEvents(int member, SetBytes.Layout layout) {
      this.member = member;
      this.layout = layout;
    }

    @Override
    public void progressed(IndexProgress latest) {
      publish();
      RotatedIndex snapshot = published;
      events.indexProgressed(
          new IndexProgress(
              snapshot.indexedBytes(),
              Math.max(totalBytes, snapshot.indexedBytes()),
              snapshot.lineCount()));
    }

    @Override
    public void finished(OperationOutcome outcome, IOException failure) {
      SetMember target = layout.members().get(member);
      if (outcome == OperationOutcome.COMPLETED || outcome == OperationOutcome.CANCELED) {
        target.record();
      }
      publish();
      if (outcome == OperationOutcome.COMPLETED && continues()) {
        try {
          indexNext();
          return;
        } catch (RejectedExecutionException | IllegalStateException e) {
          operations.stopLoading();
        }
      }
      try {
        operations.releaseIndexing();
        events.indexFinished(outcome, failure);
      } finally {
        operations.exit();
      }
      if (outcome == OperationOutcome.COMPLETED) {
        operations.tryExtend(pass, follow.stale());
      }
    }

    /** True when a later member waits and this pass moved the cursor past its member. */
    private boolean continues() {
      SetBytes.Layout now = bytes.layout();
      return member < now.members().size() - 1
          && cursor(now) > member
          && !operations.loadingStopped()
          && !operations.isClosed();
    }
  }

  @Override
  public boolean close(Duration bound) {
    boolean clean = operations.close(bound);
    List<SetMember> members = bytes.layout().members();
    if (clean) {
      for (SetMember member : members) {
        member.index().close();
      }
    }
    try {
      bytes.close();
    } catch (IOException e) {
      clean = false;
    }
    services.cache().invalidate(bytes);
    for (SetMember member : members) {
      services.cache().invalidate(member);
    }
    return clean;
  }
}
