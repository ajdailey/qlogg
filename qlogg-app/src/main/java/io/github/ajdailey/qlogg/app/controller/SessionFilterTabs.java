package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.config.Session;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.core.search.ExpressionException;
import io.github.ajdailey.qlogg.core.search.LineRange;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.search.SearchQuery;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Filter tabs in the session: a save keeps each as its parent tab's {@code W.N} and its search, and
 * a restore derives each again, with empty rows its growth fills, once its parent tab is open.
 * Confined to the UI thread.
 *
 * <p>A filter tab whose parent the save leaves out (a pasted root, a window over the tab cap) is
 * left out too. On restore, a window's file tabs are matched to their entries by path when the
 * window's restore ends; a filter tab then opens in its own window, right of its parent when the
 * parent is there, with its name and follow state, and is shown when it was the window's shown tab.
 * An entry whose parent never opens is dropped; one whose search does not parse is dropped with a
 * notice.
 */
final class SessionFilterTabs {
  /** A filter tab waiting for its parent. */
  private record Pending(
      ApplicationController window, String key, Session.Tab tab, boolean shown) {}

  /** A window whose session restore started, and its number in the session. */
  private record Restoring(ApplicationController window, int number, Session session) {}

  private final DerivedTabs tabs;
  private final WindowSet set;
  private final List<Restoring> restoring = new ArrayList<Restoring>();
  private final List<Pending> pending = new ArrayList<Pending>();
  private final Map<String, DocumentController> opened = new HashMap<String, DocumentController>();

  SessionFilterTabs(DerivedTabs tabs, WindowSet set) {
    this.tabs = tabs;
    this.set = set;
  }

  // Save.

  /**
   * Gives each filter tab of the captured windows its parent's {@code W.N} and drops the ones whose
   * parent is not saved.
   *
   * @param drafts the windows' tabs in session order
   * @return the drafts to save
   */
  List<WindowSet.WindowDraft> link(List<WindowSet.WindowDraft> drafts) {
    List<WindowSet.WindowDraft> kept = drafts;
    while (true) {
      Map<DocumentController, String> keys = keys(kept);
      List<WindowSet.WindowDraft> next = new ArrayList<WindowSet.WindowDraft>();
      boolean dropped = false;
      for (WindowSet.WindowDraft window : kept) {
        List<WindowSet.DraftTab> saved = new ArrayList<WindowSet.DraftTab>();
        int active = -1;
        for (int n = 0; n < window.tabs().size(); n++) {
          WindowSet.DraftTab draft = window.tabs().get(n);
          Derivation link = tabs.derivation(draft.tab());
          String parent = link == null ? null : keys.get(link.parent());
          if (link != null && parent == null) {
            dropped = true;
            continue;
          }
          if (n == window.active()) {
            active = saved.size();
          }
          saved.add(link == null ? draft : draft.withDerived(derived(link, parent)));
        }
        next.add(new WindowSet.WindowDraft(saved, active, window.onTop(), window.window()));
      }
      if (!dropped) {
        return next;
      }
      kept = next;
    }
  }

  /** Numbers each draft {@code W.N} in session order. */
  private static Map<DocumentController, String> keys(List<WindowSet.WindowDraft> drafts) {
    Map<DocumentController, String> keys = new HashMap<DocumentController, String>();
    for (int w = 0; w < drafts.size(); w++) {
      List<WindowSet.DraftTab> window = drafts.get(w).tabs();
      for (int n = 0; n < window.size(); n++) {
        keys.put(window.get(n).tab(), w + "." + n);
      }
    }
    return keys;
  }

  private static Session.Derived derived(Derivation link, String parent) {
    int dot = parent.indexOf('.');
    LineRange scope = link.scope();
    return new Session.Derived(
        Integer.parseInt(parent.substring(0, dot)),
        Integer.parseInt(parent.substring(dot + 1)),
        link.text(),
        RememberedState.toggles(link.options()),
        scope == null ? -1 : scope.first(),
        scope == null ? -1 : scope.end(),
        link.column());
  }

  // Restore.

  /**
   * Records a window's session as its restore starts; windows restore in session order.
   *
   * @param window the window
   * @param session its session
   */
  void restoring(ApplicationController window, Session session) {
    int number = restoring.size();
    restoring.add(new Restoring(window, number, session));
    for (int n = 0; n < session.tabs().size(); n++) {
      Session.Tab tab = session.tabs().get(n);
      if (tab.derived() != null) {
        pending.add(new Pending(window, number + "." + n, tab, n == session.active()));
      }
    }
  }

  /**
   * Matches a window's restored file tabs to their entries and derives each filter tab whose parent
   * is open.
   *
   * @param window the window whose restore ended
   */
  void restored(ApplicationController window) {
    for (Restoring started : restoring) {
      if (started.window() != window) {
        continue;
      }
      List<Session.Tab> entries = started.session().tabs();
      for (int n = 0; n < entries.size(); n++) {
        Session.Tab tab = entries.get(n);
        DocumentController file = tab.derived() == null ? fileTab(window, tab.path()) : null;
        if (file != null) {
          opened.put(started.number() + "." + n, file);
        }
      }
    }
    resolve();
  }

  private static DocumentController fileTab(ApplicationController window, String path) {
    try {
      Path given = Path.of(path);
      DocumentController tab = window.find(given);
      return tab != null ? tab : window.find(given.toAbsolutePath().normalize());
    } catch (InvalidPathException e) {
      return null;
    }
  }

  /** Derives every pending tab whose parent is open, then the tabs waiting on those. */
  private void resolve() {
    boolean progressed = true;
    while (progressed) {
      progressed = false;
      for (Pending entry : new ArrayList<Pending>(pending)) {
        Session.Derived definition = entry.tab().derived();
        DocumentController parent =
            opened.get(definition.parentWindow() + "." + definition.parentTab());
        if (parent == null) {
          continue;
        }
        pending.remove(entry);
        progressed = true;
        DocumentController created = derive(entry, parent);
        if (created != null) {
          opened.put(entry.key(), created);
        }
      }
    }
  }

  private DocumentController derive(Pending entry, DocumentController parent) {
    ApplicationController window = entry.window();
    if (parent.isClosed() || window.isShuttingDown()) {
      return null;
    }
    Session.Derived definition = entry.tab().derived();
    SearchOptions options = RememberedState.options(definition.toggles());
    LineRange scope =
        definition.scopeFirst() < 0
            ? null
            : new LineRange(definition.scopeFirst(), definition.scopeEnd());
    SearchQuery query;
    try {
      query = RanSearch.query(definition.text(), options, scope, parent.document().hideAnsi());
    } catch (ExpressionException e) {
      window.showNotice(UiText.NOTICE_FILTER_TAB_NOT_RESTORED.format(definition.text()));
      return null;
    }
    Derivation derivation =
        new Derivation(
            tabs,
            parent,
            RanSearch.unrun(definition.text(), options, query, new MutableLineSet(parent.budget())),
            set.services().search(),
            set.ui());
    derivation.setColumn(definition.column());
    DocumentController created = window.openDerived(derivation, parent.document(), entry.shown());
    if (!entry.tab().name().isEmpty()) {
      window.setTabName(created, entry.tab().name());
    }
    if (entry.tab().follow()) {
      created.setFollowing(true);
    }
    return created;
  }
}
