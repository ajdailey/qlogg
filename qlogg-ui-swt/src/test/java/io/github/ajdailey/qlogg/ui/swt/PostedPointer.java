package io.github.ajdailey.qlogg.ui.swt;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * Posts pointer events to controls and drops every pointer event the window system delivers on the
 * display. The X server's pointer sits over the test window, which receives motion and crossing
 * events while a test dispatches; a real motion between a posted press and release moves the posted
 * drag to the real pointer.
 */
final class PostedPointer {
  private static final int[] TYPES = {
    SWT.MouseDown,
    SWT.MouseUp,
    SWT.MouseMove,
    SWT.MouseEnter,
    SWT.MouseExit,
    SWT.MouseHover,
    SWT.MouseDoubleClick,
    SWT.DragDetect
  };

  private boolean posting;

  /**
   * Adds a display filter for each pointer type that empties an event not sent through {@link
   * #post}.
   *
   * @param display the test's display
   */
  PostedPointer(Display display) {
    Listener drop =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!posting) {
              event.type = SWT.None;
            }
          }
        };
    for (int type : TYPES) {
      display.addFilter(type, drop);
    }
  }

  /**
   * Sends a pointer event to a control's listeners.
   *
   * @param control the receiving control
   * @param type the event type, one of the pointer types this class filters
   * @param event the event
   */
  void post(Control control, int type, Event event) {
    posting = true;
    try {
      control.notifyListeners(type, event);
    } finally {
      posting = false;
    }
  }
}
