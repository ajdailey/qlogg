package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.FileIdentity;
import io.github.ajdailey.qlogg.core.rotate.Member;
import io.github.ajdailey.qlogg.core.rotate.MemberIdentity;
import io.github.ajdailey.qlogg.core.rotate.RotatedBytes;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The bytes of a {@link RotatedContent} set: the members and the {@link RotatedBytes} stream over
 * them, replaced together when members join. Readers keep this one source; a join changes no byte
 * at an offset the published index covers, so a read under an older snapshot stays valid.
 * Thread-safe.
 *
 * @see RotatedBytes
 */
final class SetBytes implements ByteSource {
  /**
   * The members and their stream.
   *
   * @param members oldest first
   * @param rotated the stream over them
   */
  record Layout(List<SetMember> members, RotatedBytes rotated) {
    Layout {
      members = List.copyOf(members);
    }

    SetMember last() {
      return members.get(members.size() - 1);
    }
  }

  private final Path base;
  private final TextEncoding encoding;
  private volatile Layout layout;

  private SetBytes(Path base, TextEncoding encoding, Layout layout) {
    this.base = base;
    this.encoding = encoding;
    this.layout = layout;
  }

  /**
   * Builds the stream over members. Reads each member's size.
   *
   * @param base the set's base path
   * @param encoding the set's encoding
   * @param members oldest first, at least one
   * @return the bytes; closing them closes the members
   * @throws IOException when a member cannot be read
   */
  static SetBytes of(Path base, TextEncoding encoding, List<SetMember> members) throws IOException {
    return new SetBytes(base, encoding, layout(base, encoding, members));
  }

  Layout layout() {
    return layout;
  }

  /**
   * Replaces the members, for example with joined members appended. Every member before the last
   * must have its limit set. Reads each member's size.
   *
   * @param members oldest first, the current members first and in order
   * @throws IOException when a member cannot be read; the layout stays as it was
   */
  void replace(List<SetMember> members) throws IOException {
    layout = layout(base, encoding, members);
  }

  /**
   * Follows an in-place plan: a held file renamed keeps its handle under the new name; any other
   * member whose name changed or whose reopening reads found another file is re-pointed; then the
   * joining files are appended. The caller keeps indexing from running. Blocking.
   *
   * @param plan an {@code UNCHANGED} or {@code IN_PLACE} plan for these members
   * @param services shared services
   * @param keepClosed true to open members only for each read
   * @throws IOException when a file cannot be opened or does not hold a member's bytes; the members
   *     re-pointed before stay re-pointed
   */
  void follow(RollOver.Plan plan, DocumentServices services, boolean keepClosed)
      throws IOException {
    List<SetMember> members = new ArrayList<SetMember>(layout.members());
    for (int k = 0; k < members.size(); k++) {
      SetMember member = members.get(k);
      Path next = plan.paths().get(k);
      if (member.holds(next)) {
        member.rename(next);
      } else if (!next.equals(member.path()) || member.foundOtherFile()) {
        SetMember.closeQuietly(member.repoint(next, reopen(member, next, services, keepClosed)));
      }
    }
    if (!plan.joins().isEmpty()) {
      join(members, plan.joins(), services, keepClosed);
    }
  }

  /** Opens a member's file under its new name, verified to hold the member's indexed bytes. */
  private static ByteSource reopen(
      SetMember member, Path next, DocumentServices services, boolean keepClosed)
      throws IOException {
    ByteSource opened = SetMember.openFile(next, services.heldLock(), keepClosed);
    MemberIdentity identity = member.memberIdentity();
    boolean same =
        identity == null
            || (identity.fileKey() != null
                && identity.fileKey().equals(opened.identity().fileKey()))
            || identity.byContent().find(List.of(next)).outcome() == MemberIdentity.Outcome.FOUND;
    if (!same) {
      SetMember.closeQuietly(opened);
      throw new IOException(next + " no longer holds the member's bytes");
    }
    return opened;
  }

  /** Appends the joining files; every member before the new last keeps its current size. */
  private void join(
      List<SetMember> members, List<Member> joins, DocumentServices services, boolean keepClosed)
      throws IOException {
    List<SetMember> added = new ArrayList<SetMember>();
    List<SetMember> limited = new ArrayList<SetMember>();
    boolean joined = false;
    try {
      for (Member file : joins) {
        added.add(
            SetMember.open(
                file.path(), file.encoding().bomLength(), encoding, services, keepClosed));
      }
      members.addAll(added);
      for (int i = 0; i < members.size() - 1; i++) {
        SetMember member = members.get(i);
        if (member.limit() == Long.MAX_VALUE) {
          limited.add(member);
          member.limit(member.file().size());
        }
      }
      replace(members);
      joined = true;
    } finally {
      if (!joined) {
        for (SetMember member : limited) {
          member.limit(Long.MAX_VALUE);
        }
        for (SetMember member : added) {
          member.index().close();
          SetMember.closeQuietly(member);
        }
      }
    }
  }

  private static Layout layout(Path base, TextEncoding encoding, List<SetMember> members)
      throws IOException {
    List<RotatedBytes.Part> parts = new ArrayList<RotatedBytes.Part>();
    for (SetMember member : members) {
      parts.add(new RotatedBytes.Part(member, member.bomLength()));
    }
    return new Layout(members, RotatedBytes.open(base, encoding, parts));
  }

  @Override
  public Path path() {
    return base;
  }

  @Override
  public long size() throws IOException {
    return layout.rotated().size();
  }

  @Override
  public int read(long position, ByteBuffer dst) throws IOException {
    return layout.rotated().read(position, dst);
  }

  /** Returns the last member's identity. */
  @Override
  public FileIdentity identity() {
    return layout.rotated().identity();
  }

  /** Closes every member. */
  @Override
  public void close() throws IOException {
    layout.rotated().close();
  }
}
