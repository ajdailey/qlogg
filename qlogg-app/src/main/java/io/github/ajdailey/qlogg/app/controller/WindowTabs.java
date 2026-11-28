package io.github.ajdailey.qlogg.app.controller;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One window's tabs in order, where a new tab goes, and their Rename Tab names. Confined to the UI
 * thread.
 *
 * <p>A file tab opens after the last tab; a filter tab opens right of its parent, after the
 * parent's filter tabs at every depth that this window holds. A file tab's name is kept by path, so
 * it survives a reload and reopening the file; a filter tab's name is kept by tab and moves to the
 * tab's replacement. The order in which tabs were shown ({@link TabUse}) picks the tab shown after
 * a close.
 */
final class WindowTabs {
  private final List<DocumentController> tabs = new ArrayList<DocumentController>();
  private final List<DocumentController> view = Collections.unmodifiableList(tabs);
  private final Map<Path, String> fileNames = new HashMap<Path, String>();
  private final Map<DocumentController, String> filterNames =
      new HashMap<DocumentController, String>();
  private final TabUse use = new TabUse();
  private final List<ApplicationListener> listeners;

  /**
   * Creates the tabs.
   *
   * @param listeners the window's listeners, told of each tab opened or moved in
   */
  WindowTabs(List<ApplicationListener> listeners) {
    this.listeners = listeners;
  }

  /** Returns the tabs in order, as a read-only view. */
  List<DocumentController> list() {
    return view;
  }

  /** Returns the order the tabs were shown in. */
  TabUse use() {
    return use;
  }

  /**
   * Adds a new tab where it belongs, a file tab last and a filter tab after its parent and the
   * parent's filter tabs here, and fires {@link ApplicationListener#documentOpened}.
   *
   * @param tab the new tab
   * @param derived the filter tabs' parent links
   */
  void open(DocumentController tab, DerivedTabs derived) {
    Derivation link = derived.derivation(tab);
    int index = link == null ? -1 : tabs.indexOf(link.parent());
    if (index < 0) {
      index = tabs.size();
    } else {
      index++;
      while (index < tabs.size() && derived.descends(tabs.get(index), link.parent())) {
        index++;
      }
    }
    tabs.add(index, tab);
    for (ApplicationListener listener : new ArrayList<ApplicationListener>(listeners)) {
      listener.documentOpened(tab, index);
    }
  }

  /**
   * Inserts a tab moved from another window and fires {@link ApplicationListener#documentMovedIn}.
   *
   * @param tab the tab
   * @param index the position, clamped to the tabs
   */
  void moveIn(DocumentController tab, int index) {
    int at = Math.max(0, Math.min(index, tabs.size()));
    tabs.add(at, tab);
    for (ApplicationListener listener : new ArrayList<ApplicationListener>(listeners)) {
      listener.documentMovedIn(tab, at);
    }
  }

  /**
   * Moves a tab, as dragging it does.
   *
   * @param tab an open tab
   * @param index its new position, clamped to the tabs
   * @return false when the tab is not open here
   */
  boolean move(DocumentController tab, int index) {
    if (!tabs.remove(tab)) {
      return false;
    }
    tabs.add(Math.max(0, Math.min(index, tabs.size())), tab);
    return true;
  }

  /**
   * Removes a tab and a filter tab's name; a file tab's name stays for its path.
   *
   * @param tab the tab
   * @return its former index, or -1 when it was not open here
   */
  int remove(DocumentController tab) {
    int index = tabs.indexOf(tab);
    if (index >= 0) {
      tabs.remove(index);
      filterNames.remove(tab);
    }
    return index;
  }

  /**
   * Puts a replacement in an open tab's place, with the tab's filter-tab name.
   *
   * @param old the open tab
   * @param replacement its replacement
   */
  void replace(DocumentController old, DocumentController replacement) {
    tabs.set(tabs.indexOf(old), replacement);
    use.replaced(old, replacement);
    String name = filterNames.remove(old);
    if (name != null) {
      filterNames.put(replacement, name);
    }
  }

  /** Removes every tab and the shown order; names stay. */
  void clear() {
    tabs.clear();
    use.clear();
  }

  /**
   * Returns the file tab showing a path.
   *
   * @param path a path, or null
   * @return the tab, or null
   */
  DocumentController find(Path path) {
    for (DocumentController tab : tabs) {
      if (path != null && tab.ownsDocument() && path.equals(tab.document().path())) {
        return tab;
      }
    }
    return null;
  }

  /**
   * Lists the tabs on one side of a tab.
   *
   * @param from an open tab
   * @param left true for the tabs before it
   * @return a copy; empty when the tab is not open here
   */
  List<DocumentController> side(DocumentController from, boolean left) {
    int index = tabs.indexOf(from);
    if (index < 0) {
      return new ArrayList<DocumentController>();
    }
    return new ArrayList<DocumentController>(
        left ? tabs.subList(0, index) : tabs.subList(index + 1, tabs.size()));
  }

  /**
   * Returns the tab a step away, wrapping around.
   *
   * @param from an open tab
   * @param step +1 for the next tab, -1 for the previous one
   * @return the tab, or null with fewer than two tabs or {@code from} not open here
   */
  DocumentController neighbor(DocumentController from, int step) {
    int index = tabs.indexOf(from);
    if (index < 0 || tabs.size() < 2) {
      return null;
    }
    return tabs.get(Math.floorMod(index + step, tabs.size()));
  }

  // Rename Tab.

  /**
   * Returns a tab's Rename Tab name.
   *
   * @param tab a tab
   * @return the name, or null when it has none
   */
  String name(DocumentController tab) {
    if (!tab.ownsDocument()) {
      return filterNames.get(tab);
    }
    Path path = tab.document().path();
    return path == null ? null : fileNames.get(path);
  }

  /**
   * Sets a tab's name, cut to {@link ApplicationController#TAB_NAME_CHARS}; blank removes it.
   *
   * @param tab a tab with a file
   * @param name the name
   */
  void setName(DocumentController tab, String name) {
    String label = name.strip();
    if (label.length() > ApplicationController.TAB_NAME_CHARS) {
      label = label.substring(0, ApplicationController.TAB_NAME_CHARS);
    }
    if (!tab.ownsDocument()) {
      put(filterNames, tab, label);
    } else {
      put(fileNames, tab.document().path(), label);
    }
  }

  /**
   * Removes a tab's name, as it moves to another window.
   *
   * @param tab a tab
   * @return the name, or null
   */
  String takeName(DocumentController tab) {
    String name = name(tab);
    setName(tab, "");
    return name;
  }

  private static <K> void put(Map<K, String> names, K key, String label) {
    if (label.isEmpty()) {
      names.remove(key);
    } else {
      names.put(key, label);
    }
  }
}
