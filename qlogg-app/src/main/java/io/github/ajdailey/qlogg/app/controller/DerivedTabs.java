package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The filter tabs of every window and their parent links. A parent link names the parent's
 * controller, not its window, so a tab and its parent may be in different windows. Confined to the
 * UI thread.
 *
 * <p>Closing a file tab closes its filter tabs at every depth; closing a filter tab moves its
 * children under its parent. A file tab's replacement on a reload or a change on disk derives each
 * of its filter tabs again over the new document, a level after its parent, with empty rows its
 * growth fills. Locate Source Line shows a filter tab's caret line in its immediate parent.
 */
final class DerivedTabs {
  private final WindowSet set;
  private final Map<DocumentController, Derivation> derived =
      new LinkedHashMap<DocumentController, Derivation>();
  private final SessionFilterTabs sessions;

  DerivedTabs(WindowSet set) {
    this.set = set;
    this.sessions = new SessionFilterTabs(this, set);
  }

  /** Returns the filter tabs' session save and restore. */
  SessionFilterTabs sessions() {
    return sessions;
  }

  /**
   * Returns a filter tab's definition.
   *
   * @param tab a tab
   * @return the definition, or null for a file tab
   */
  Derivation derivation(DocumentController tab) {
    return derived.get(tab);
  }

  /**
   * Returns the file tab at the top of a tab's chain.
   *
   * @param tab a tab
   * @return the root tab; {@code tab} itself for a file tab
   */
  DocumentController root(DocumentController tab) {
    DocumentController at = tab;
    Derivation link = derived.get(at);
    while (link != null) {
      at = link.parent();
      link = derived.get(at);
    }
    return at;
  }

  /**
   * Makes a filter tab of the shown result set of a tab's search when it ran to its end: copies the
   * set on a {@code qlogg-search-} worker, then opens the tab in the window holding the parent. A
   * set still running, failed or canceled, and a copy the {@code RESULTS} share refuses, show a
   * notice and open nothing.
   *
   * @param window the window the command ran in
   * @param parent the tab
   */
  void create(final ApplicationController window, final DocumentController parent) {
    final RanSearch ran = parent.search().shownRan();
    if (ran == null || parent.isClosed()) {
      window.showNotice(UiText.NOTICE_FILTER_TAB_NOT_DONE.text());
      return;
    }
    new BusyWork(parent.ui(), parent.activity()) {
      private MutableLineSet copy;

      @Override
      @WorkerThread
      void work() {
        copy = ran.lines.copy(parent.budget());
      }

      @Override
      void finished(boolean completed) {
        copied(window, parent, ran, copy);
      }
    }.start(parent.workers(), Workers.Kind.SEARCH);
  }

  private void copied(
      ApplicationController window, DocumentController parent, RanSearch ran, MutableLineSet copy) {
    ApplicationController holder = holder(parent);
    if (copy == null) {
      window.showNotice(UiText.NOTICE_FILTER_TAB_MEMORY.text());
      return;
    }
    if (holder == null || parent.isClosed()) {
      copy.clear();
      return;
    }
    holder.openDerived(
        new Derivation(this, parent, ran.over(copy), set.services().search(), set.ui()),
        parent.document(),
        true);
  }

  /**
   * Records a filter tab once its controller is built, before it starts.
   *
   * @param derivation the tab's definition, attached to its controller
   */
  void register(Derivation derivation) {
    derived.put(derivation.owner(), derivation);
  }

  /**
   * Starts the growth of a tab's children after its rows grew.
   *
   * @param parent the tab
   */
  void grew(DocumentController parent) {
    for (Derivation child : children(parent)) {
      child.grow();
    }
  }

  /**
   * Handles a tab closing in any window, before it leaves its window: a file tab closes its filter
   * tabs, and a filter tab moves its children under its own parent.
   *
   * @param tab the closing tab
   */
  void closing(DocumentController tab) {
    Derivation own = derived.remove(tab);
    if (own != null) {
      for (Derivation child : children(tab)) {
        child.reparent(own.parent());
      }
      return;
    }
    List<DocumentController> closing = descendants(tab);
    for (DocumentController descendant : closing) {
      derived.remove(descendant);
    }
    for (DocumentController descendant : closing) {
      ApplicationController holder = holder(descendant);
      if (holder != null) {
        holder.closeDocument(descendant);
      }
    }
  }

  /**
   * Derives each filter tab of a replaced file tab again over the replacement's document, parents
   * before children; each takes its tab's place in its window.
   *
   * @param old the replaced file tab
   * @param replacement its replacement
   * @param change the change on disk, or null for a reload
   */
  void replaced(DocumentController old, DocumentController replacement, FileChange change) {
    Map<DocumentController, DocumentController> moved =
        new HashMap<DocumentController, DocumentController>();
    moved.put(old, replacement);
    for (DocumentController tab : descendants(old)) {
      Derivation before = derived.remove(tab);
      DocumentController parent = moved.get(before.parent());
      ApplicationController holder = holder(tab);
      if (parent == null || holder == null) {
        continue;
      }
      Derivation again = before.rederived(parent, replacement.budget());
      moved.put(tab, holder.replaceDerived(tab, again, change));
    }
  }

  /**
   * Shows a filter tab's line in its immediate parent: the parent's tab is shown, its window raised
   * when it is another one, and the move recorded as a jump of the parent's history.
   *
   * @param window the window the command ran in
   * @param tab the filter tab
   * @param line the root line, the caret line of its main view or filtered view
   * @return false for a file tab, which has no parent, or no line
   */
  boolean locate(ApplicationController window, DocumentController tab, long line) {
    Derivation link = derived.get(tab);
    if (link == null) {
      return false;
    }
    DocumentController parent = link.parent();
    ApplicationController holder = holder(parent);
    if (holder == null || line < 0) {
      return false;
    }
    holder.activate(parent);
    if (holder != window) {
      holder.toFront();
    }
    parent.recordJump();
    parent.showLine(line);
    return true;
  }

  /**
   * Reports whether a tab is a filter tab of another at any depth.
   *
   * @param tab a tab
   * @param ancestor another tab
   * @return true when {@code ancestor} is on {@code tab}'s chain
   */
  boolean descends(DocumentController tab, DocumentController ancestor) {
    Derivation link = derived.get(tab);
    while (link != null) {
      if (link.parent() == ancestor) {
        return true;
      }
      link = derived.get(link.parent());
    }
    return false;
  }

  /** Returns the window holding a tab, or null. */
  ApplicationController holder(DocumentController tab) {
    for (ApplicationController window : set.windows()) {
      if (window.documents().contains(tab)) {
        return window;
      }
    }
    return null;
  }

  private List<Derivation> children(DocumentController parent) {
    List<Derivation> found = new ArrayList<Derivation>();
    for (Derivation link : derived.values()) {
      if (link.parent() == parent) {
        found.add(link);
      }
    }
    return found;
  }

  /** Lists a tab's filter tabs at every depth, each after its parent. */
  private List<DocumentController> descendants(DocumentController tab) {
    List<DocumentController> found = new ArrayList<DocumentController>();
    found.add(tab);
    for (int i = 0; i < found.size(); i++) {
      for (Map.Entry<DocumentController, Derivation> entry : derived.entrySet()) {
        if (entry.getValue().parent() == found.get(i)) {
          found.add(entry.getKey());
        }
      }
    }
    found.remove(0);
    return found;
  }
}
