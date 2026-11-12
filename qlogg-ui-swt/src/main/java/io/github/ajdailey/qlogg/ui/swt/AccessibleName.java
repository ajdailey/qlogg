package io.github.ajdailey.qlogg.ui.swt;

import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.widgets.Control;

/** The name a screen reader reads for a control, settable after the control is built. */
final class AccessibleName extends AccessibleAdapter {
  private String name;

  private AccessibleName(String name) {
    this.name = name;
  }

  /**
   * Names a control.
   *
   * @param control the control
   * @param name the name
   * @return the listener, through which the name changes
   */
  static AccessibleName attach(Control control, String name) {
    AccessibleName listener = new AccessibleName(name);
    control.getAccessible().addAccessibleListener(listener);
    return listener;
  }

  void set(String value) {
    name = value;
  }

  String name() {
    return name;
  }

  @Override
  public void getName(AccessibleEvent event) {
    if (event.childID == ACC.CHILDID_SELF) {
      event.result = name;
    }
  }
}
