package io.github.ajdailey.qlogg.core.archfixture;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Core classes that each break one architecture rule; never instantiated. */
final class CoreViolations {
  private CoreViolations() {}

  /** Declares collection classes as a field, a generic parameter argument and a return type. */
  static final class DeclaresClasses {
    final ArrayList<String> items = new ArrayList<String>();
    final List<String> names = new ArrayList<String>();

    int index(Map<String, TreeSet<String>> words) {
      return words.size();
    }

    ArrayDeque<String> pending() {
      return new ArrayDeque<String>();
    }
  }
}
