package io.github.ajdailey.qlogg.ui.swt;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * Lays a tab strip's parent out again when the strip's height differs from its computed size.
 * {@code CTabFolder} measures its top-right control on a later UI turn, through {@code asyncExec}.
 * A strip filled and laid out in the turn that created it, as in a rebuilt window, is first sized
 * with the control as a wrapped row under the tabs; once the folder measures the control, the
 * folder's client area grows by that row and stays empty until the parent lays out again. Created
 * and used on the UI thread.
 */
final class TabStripHeight implements Listener {
  private final CTabFolder tabs;
  private boolean laying;

  private TabStripHeight(CTabFolder tabs) {
    this.tabs = tabs;
  }

  /**
   * Follows a strip laid out by a {@code GridLayout} with a {@link GridData} height hint.
   *
   * @param tabs the strip
   */
  static void follow(CTabFolder tabs) {
    tabs.addListener(SWT.Resize, new TabStripHeight(tabs));
  }

  @Override
  public void handleEvent(Event event) {
    if (laying || tabs.isDisposed() || !(tabs.getLayoutData() instanceof GridData data)) {
      return;
    }
    Point size = tabs.getSize();
    if (data.exclude || size.x == 0 || tabs.computeSize(size.x, data.heightHint).y == size.y) {
      return;
    }
    laying = true;
    try {
      tabs.getParent().layout(new Control[] {tabs});
    } finally {
      laying = false;
    }
  }
}
