package io.github.ajdailey.qlogg.app.controller;

import io.github.ajdailey.qlogg.app.highlight.HighlightSettings;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.view.ActivityIndicator;
import io.github.ajdailey.qlogg.app.view.ClipboardService;
import io.github.ajdailey.qlogg.app.view.MainView;
import io.github.ajdailey.qlogg.app.view.NotificationService;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The window a document's tab is in, as that document's controllers see it: each call goes to the
 * current window's {@link ApplicationController} or {@link MainView}. {@link #moveTo} changes the
 * window when the tab moves; the controllers keep this object. Confined to the UI thread, except
 * the {@link ActivityIndicator} methods.
 *
 * <p>Activity: each window the tab has been in keeps a count of the operations reported active to
 * it. {@link #setActive} reports to the current window; {@link #setIdle} goes to the oldest window
 * with a count above 0, so an operation that started before a move ends the busy state of the
 * window it was reported to. {@link #setActive} calls the indicator before it counts, so a {@link
 * #setIdle} that finds the count can never reach the indicator first. A window's count stays in the
 * list while the window is open or the count is above 0; at most one entry per window.
 */
final class TabHost
    implements DocumentHost, ClipboardService, NotificationService, ActivityIndicator {
  /** One window's indicator and the operations reported active to it. */
  private record Binding(
      ApplicationController window, ActivityIndicator indicator, AtomicInteger active) {}

  private final AtomicReference<List<Binding>> bindings;
  private ApplicationController window;
  private MainView view;

  /**
   * Creates the host for a tab in a window.
   *
   * @param window the window's controller
   * @param view the window
   */
  TabHost(ApplicationController window, MainView view) {
    this.window = window;
    this.view = view;
    this.bindings =
        new AtomicReference<List<Binding>>(List.of(new Binding(window, view, new AtomicInteger())));
  }

  /**
   * Sends later calls to another window. Drops the counts of closed windows that have none active.
   *
   * @param target the window's controller
   * @param targetView the window
   * @param open the windows still open
   */
  void moveTo(ApplicationController target, MainView targetView, List<ApplicationController> open) {
    window = target;
    view = targetView;
    List<Binding> kept = new ArrayList<Binding>();
    Binding current = null;
    for (Binding binding : bindings.get()) {
      if (binding.window() == target) {
        current = binding;
      } else if (open.contains(binding.window()) || binding.active().get() > 0) {
        kept.add(binding);
      }
    }
    kept.add(current == null ? new Binding(target, targetView, new AtomicInteger()) : current);
    bindings.set(List.copyOf(kept));
  }

  @Override
  public void setActive() {
    List<Binding> list = bindings.get();
    Binding current = list.get(list.size() - 1);
    current.indicator().setActive();
    current.active().incrementAndGet();
  }

  @Override
  public void setIdle() {
    for (Binding binding : bindings.get()) {
      AtomicInteger active = binding.active();
      int count = active.get();
      while (count > 0) {
        if (active.compareAndSet(count, count - 1)) {
          binding.indicator().setIdle();
          return;
        }
        count = active.get();
      }
    }
  }

  @Override
  public void setText(String text) {
    view.setText(text);
  }

  @Override
  public String clipboardText() {
    return view.clipboardText();
  }

  @Override
  public void showError(String title, String message) {
    view.showError(title, message);
  }

  @Override
  public void showInfo(String title, String message) {
    view.showInfo(title, message);
  }

  @Override
  public void showNotice(String message) {
    view.showNotice(message);
  }

  @Override
  public SearchOptions initialOptions() {
    return window.initialOptions();
  }

  @Override
  public void searchRan(String text, SearchOptions options) {
    window.searchRan(text, options);
  }

  @Override
  public boolean runSearchOnAdd() {
    return window.runSearchOnAdd();
  }

  @Override
  public HighlightSettings highlightSettings() {
    return window.highlightSettings();
  }

  @Override
  public Palette palette() {
    return window.palette();
  }

  @Override
  public boolean followOnOverscroll() {
    return window.followOnOverscroll();
  }

  @Override
  public void reopenChanged(DocumentController document, FileChange change) {
    window.reopenChanged(document, change);
  }

  @Override
  public void documentStateChanged() {
    window.documentStateChanged();
  }
}
