package io.github.ajdailey.qlogg.app.controller;

import java.util.ArrayList;
import java.util.List;

/**
 * The order in which one window's tabs were last shown, and the tab shown after the shown tab
 * closes: the tab after it, else the one before; with {@code tabs.closeShowsLastUsed} on, the most
 * recently shown open tab. Confined to the UI thread.
 */
final class TabUse {
  /** Tabs most recently shown first; a closed or moved-out tab is not listed. */
  private final List<DocumentController> order = new ArrayList<DocumentController>();

  /** Records a tab as the one shown now. */
  void shown(DocumentController document) {
    if (order.isEmpty() || order.get(0) != document) {
      order.remove(document);
      order.add(0, document);
    }
  }

  /** Drops a closed or moved-out tab. */
  void removed(DocumentController document) {
    order.remove(document);
  }

  /** Puts a reloaded tab's controller in its predecessor's place. */
  void replaced(DocumentController old, DocumentController replacement) {
    int at = order.indexOf(old);
    if (at >= 0) {
      order.set(at, replacement);
    }
  }

  /** Forgets every tab. */
  void clear() {
    order.clear();
  }

  /**
   * Returns the tab to show after the shown one closed.
   *
   * @param open the open tabs in tab order, the closed one already removed
   * @param index the closed tab's former index
   * @param lastUsed {@code tabs.closeShowsLastUsed}
   * @return the tab, or null when none is open
   */
  DocumentController successor(List<DocumentController> open, int index, boolean lastUsed) {
    if (open.isEmpty()) {
      return null;
    }
    if (lastUsed) {
      for (DocumentController document : order) {
        if (open.contains(document)) {
          return document;
        }
      }
    }
    return open.get(Math.min(index, open.size() - 1));
  }
}
