package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.time.TimestampParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The time sync groups of every window, and Scroll All Tabs to This Time. Confined to the UI
 * thread; one small object per group, not charged to the budget.
 *
 * <p>A group is a set of tabs, a tab is in at most one, and a group of one dissolves. Membership
 * follows the {@link DocumentController}, so a tab keeps it through a move between windows; {@link
 * #replace} carries it through a reload or reopen, and closing the tab removes it.
 *
 * <p>A member's caret move ({@link TimeController.SyncListener#moved}) cancels the members' running
 * sync moves, and {@link #DEBOUNCE_MILLIS} ms after the group's last move the source's caret time
 * goes to every other member through {@link TimeController#syncTo}, which reports no move, so
 * members cannot bounce. A newer move drops the pending one.
 *
 * <p>A restored tab forms a group only with a second tab restored under the same session number
 * ({@link #restore}); until then it waits in {@code restored}, so a group that comes back with one
 * open member never exists.
 */
public final class TimeSyncGroups {
  /** Delay between a member's last caret move and the sync. */
  public static final int DEBOUNCE_MILLIS = 100;

  /**
   * One item of a tab's Time Sync cascade.
   *
   * @param document the tab the item names
   * @param label its title, with its group number when it is in another group
   * @param checked true when it is in the cascade's tab's group
   */
  public record Entry(DocumentController document, String label, boolean checked) {}

  /** A group: its number for the tooltip and the cascade, its members and its last move. */
  private static final class Group {
    private final int number;
    private final List<DocumentController> members = new ArrayList<DocumentController>();
    private long moves;

    Group(int number) {
      this.number = number;
    }
  }

  private final WindowSet set;
  private final List<Group> groups = new ArrayList<Group>();
  // A tab of each session number, while more tabs of it may arrive.
  private final Map<Integer, DocumentController> restored =
      new HashMap<Integer, DocumentController>();
  private final TimeController.SyncListener listener =
      new TimeController.SyncListener() {
        @Override
        public void moved(DocumentController source) {
          memberMoved(source);
        }

        @Override
        public void detached(DocumentController document) {
          memberDetached(document);
        }
      };

  TimeSyncGroups(WindowSet set) {
    this.set = set;
  }

  /**
   * Returns a tab's group number.
   *
   * @param document the tab
   * @return the number from 1, or 0 outside a group
   */
  public int number(DocumentController document) {
    Group group = groupOf(document);
    return group == null ? 0 : group.number;
  }

  /**
   * Lists the items of a tab's cascade: every other tab of every window that has a format. A tab
   * without a format lists none.
   *
   * @param tab the tab
   * @return the items in window and tab order
   */
  public List<Entry> entries(DocumentController tab) {
    List<Entry> entries = new ArrayList<Entry>();
    if (tab.time().format() == null) {
      return entries;
    }
    Group mine = groupOf(tab);
    for (ApplicationController window : set.windows()) {
      for (DocumentController other : window.documents()) {
        if (other == tab || other.time().format() == null) {
          continue;
        }
        Group theirs = groupOf(other);
        String title = window.tabTitle(other);
        boolean same = theirs != null && theirs == mine;
        String label =
            theirs == null || same
                ? title
                : UiText.TIME_SYNC_OTHER_GROUP.format(title, theirs.number);
        entries.add(new Entry(other, label, same));
      }
    }
    return entries;
  }

  /**
   * Applies a check item of a tab's cascade. A tab of this tab's group leaves it. A tab outside any
   * group joins this tab, a new group if need be, and moves to this tab's time. A tab of another
   * group takes this tab in, which leaves its group, and this tab moves to that tab's time.
   *
   * @param tab the cascade's tab
   * @param other the tab the item names
   */
  public void toggle(DocumentController tab, DocumentController other) {
    if (tab == other || tab.isClosed() || other.isClosed()) {
      return;
    }
    Group mine = groupOf(tab);
    Group theirs = groupOf(other);
    if (mine != null && mine == theirs) {
      leave(other);
      return;
    }
    if (theirs == null) {
      if (mine == null) {
        mine = newGroup();
        add(mine, tab);
      }
      add(mine, other);
      moveTo(other, tab);
    } else {
      remove(tab);
      add(theirs, tab);
      moveTo(tab, other);
    }
    changed();
  }

  /**
   * Takes a tab out of its group.
   *
   * @param tab the tab
   */
  public void leave(DocumentController tab) {
    Group group = groupOf(tab);
    if (group != null) {
      remove(tab);
      forget(tab, group);
      tab.time().setSyncListener(null);
      changed();
    }
  }

  /**
   * Moves every other tab of every window to the caret time of a tab, or shows a notice when the
   * caret line has none. Each tab converts with its own offset.
   *
   * @param source the tab
   */
  void scrollAll(final DocumentController source) {
    source
        .time()
        .readCaretTime(
            new TimeController.CaretTimeListener() {
              @Override
              public void caretTime(long shown) {
                if (shown == TimestampParser.NONE) {
                  source.notifications().showNotice(UiText.NOTICE_TIME_NO_CARET.text());
                  return;
                }
                for (ApplicationController window : set.windows()) {
                  for (DocumentController other : window.documents()) {
                    if (other != source) {
                      other.time().syncTo(shown);
                    }
                  }
                }
              }
            });
  }

  /**
   * Gives a reloaded or reopened tab's membership to its new controller; call before the old one
   * closes.
   *
   * @param old the closing controller
   * @param replacement the new controller
   */
  void replace(DocumentController old, DocumentController replacement) {
    Group group = groupOf(old);
    if (group != null) {
      group.members.set(group.members.indexOf(old), replacement);
    }
    boolean waiting = false;
    for (Map.Entry<Integer, DocumentController> entry : restored.entrySet()) {
      if (entry.getValue() == old) {
        entry.setValue(replacement);
        waiting = true;
      }
    }
    if (group != null || waiting) {
      old.time().setSyncListener(null);
      replacement.time().setSyncListener(listener);
    }
  }

  /**
   * Returns the number a tab's group saves under: its place among the groups with a saved tab, so
   * the numbers run from 1. Pasted tabs are not saved.
   *
   * @param document the tab
   * @return the number, or 0 outside a group
   */
  int sessionNumber(DocumentController document) {
    Group group = groupOf(document);
    if (group == null) {
      return 0;
    }
    int number = 0;
    for (Group other : groups) {
      if (saved(other)) {
        number++;
      }
      if (other == group) {
        break;
      }
    }
    return number;
  }

  /**
   * Adds a restored tab to the group of a session number.
   *
   * @param number the number, or 0 for no group
   * @param document the tab
   */
  void restore(int number, DocumentController document) {
    if (number <= 0) {
      return;
    }
    DocumentController peer = restored.get(number);
    document.time().setSyncListener(listener);
    if (peer == null) {
      restored.put(number, document);
      return;
    }
    Group group = groupOf(peer);
    if (group == null) {
      group = newGroup();
      add(group, peer);
    }
    add(group, document);
    changed();
  }

  private static boolean saved(Group group) {
    for (DocumentController member : group.members) {
      if (!member.document().inMemory()) {
        return true;
      }
    }
    return false;
  }

  private Group groupOf(DocumentController document) {
    for (Group group : groups) {
      if (group.members.contains(document)) {
        return group;
      }
    }
    return null;
  }

  private Group newGroup() {
    int number = 1;
    boolean taken = true;
    while (taken) {
      taken = false;
      for (Group group : groups) {
        if (group.number == number) {
          taken = true;
          number++;
        }
      }
    }
    Group group = new Group(number);
    groups.add(group);
    return group;
  }

  private void add(Group group, DocumentController document) {
    group.members.add(document);
    document.time().setSyncListener(listener);
  }

  /**
   * Takes a tab out of its group, if any, ending its sync move; a group left with one member
   * dissolves.
   */
  private void remove(DocumentController document) {
    Group group = groupOf(document);
    if (group == null) {
      return;
    }
    group.members.remove(document);
    document.time().cancelSync();
    if (group.members.size() == 1) {
      DocumentController last = group.members.remove(0);
      last.time().cancelSync();
      last.time().setSyncListener(null);
      forget(last, null);
      groups.remove(group);
    }
  }

  /** Stops waiting on a tab; another member of its group takes its place. */
  private void forget(DocumentController document, Group group) {
    Iterator<Map.Entry<Integer, DocumentController>> entries = restored.entrySet().iterator();
    while (entries.hasNext()) {
      Map.Entry<Integer, DocumentController> entry = entries.next();
      if (entry.getValue() == document) {
        if (group == null || group.members.isEmpty()) {
          entries.remove();
        } else {
          entry.setValue(group.members.get(0));
        }
      }
    }
  }

  private void changed() {
    set.timeSyncChanged();
    set.scheduleSave();
  }

  private void memberDetached(DocumentController document) {
    Group group = groupOf(document);
    remove(document);
    forget(document, group);
    if (group != null && !set.isQuitting()) {
      changed();
    }
  }

  private void memberMoved(final DocumentController source) {
    final Group group = groupOf(source);
    if (group == null) {
      return;
    }
    final long move = ++group.moves;
    for (DocumentController member : group.members) {
      member.time().cancelSync();
    }
    set.ui()
        .timerExec(
            DEBOUNCE_MILLIS,
            new Runnable() {
              @Override
              public void run() {
                if (move == group.moves && group.members.contains(source)) {
                  spread(group, source, move);
                }
              }
            });
  }

  /** Reads the source's caret time and moves the other members there, unless a move came first. */
  private void spread(final Group group, final DocumentController source, final long move) {
    source
        .time()
        .readCaretTime(
            new TimeController.CaretTimeListener() {
              @Override
              public void caretTime(long shown) {
                if (shown == TimestampParser.NONE || move != group.moves) {
                  return;
                }
                for (DocumentController member : group.members) {
                  if (member != source) {
                    member.time().syncTo(shown);
                  }
                }
              }
            });
  }

  /** Moves a tab to another tab's caret time. */
  private static void moveTo(final DocumentController tab, DocumentController timeOf) {
    timeOf
        .time()
        .readCaretTime(
            new TimeController.CaretTimeListener() {
              @Override
              public void caretTime(long shown) {
                if (shown != TimestampParser.NONE) {
                  tab.time().syncTo(shown);
                }
              }
            });
  }
}
