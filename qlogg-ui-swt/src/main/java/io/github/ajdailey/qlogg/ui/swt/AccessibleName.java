package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.ui.swt.platform.AccessibleLabels;
import io.github.ajdailey.qlogg.ui.swt.platform.PlatformTraits;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.widgets.Control;

/**
 * The name a screen reader reads for a control, settable after the control is built. The platform's
 * {@link AccessibleLabels} adds the description and role its screen reader needs.
 */
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
    return attach(control, name, PlatformTraits.PLATFORM.accessibleLabels());
  }

  /**
   * Names a control, with a platform's description and role.
   *
   * @param control the control
   * @param name the name
   * @param labels the platform's labels
   * @return the listener, through which the name changes
   */
  static AccessibleName attach(Control control, String name, AccessibleLabels labels) {
    final AccessibleName listener = new AccessibleName(name);
    control.getAccessible().addAccessibleListener(listener);
    labels.describe(
        control,
        new AccessibleAdapter() {
          @Override
          public void getDescription(AccessibleEvent event) {
            if (event.childID == ACC.CHILDID_SELF) {
              event.result = listener.name;
            }
          }
        });
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
