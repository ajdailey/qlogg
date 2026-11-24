package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleControlAdapter;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.accessibility.AccessibleListener;
import org.eclipse.swt.widgets.Control;

/** cocoa: the name is also the description, and the control reports a group role. */
final class CocoaAccessibleLabels implements AccessibleLabels {
  /** Reports {@link ACC#ROLE_GROUP} for the control itself. */
  static final AccessibleControlAdapter GROUP_ROLE =
      new AccessibleControlAdapter() {
        @Override
        public void getRole(AccessibleControlEvent event) {
          if (event.childID == ACC.CHILDID_SELF) {
            event.detail = ACC.ROLE_GROUP;
          }
        }
      };

  @Override
  public void describe(Control control, AccessibleListener description) {
    control.getAccessible().addAccessibleListener(description);
    control.getAccessible().addAccessibleControlListener(GROUP_ROLE);
  }
}
