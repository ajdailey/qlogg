package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.ui.swt.platform.PlatformTraits;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleControlAdapter;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.widgets.Control;

/**
 * The name a screen reader reads for a control, settable after the control is built. Where {@link
 * PlatformTraits#labelsByDescription} holds (cocoa), the name is also the accessible description
 * and the control reports {@link ACC#ROLE_GROUP}, so VoiceOver reads the name and "group" instead
 * of SWT's {@code SWTComposite} role; elsewhere only the name is set.
 */
final class AccessibleName extends AccessibleAdapter {
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

  private final boolean described;
  private String name;

  private AccessibleName(String name, boolean described) {
    this.name = name;
    this.described = described;
  }

  /**
   * Names a control.
   *
   * @param control the control
   * @param name the name
   * @return the listener, through which the name changes
   */
  static AccessibleName attach(Control control, String name) {
    return attach(control, name, PlatformTraits.PLATFORM.labelsByDescription());
  }

  /**
   * Names a control, also as its description and with a group role when {@code described}.
   *
   * @param control the control
   * @param name the name
   * @param described true to set the description and role
   * @return the listener, through which the name changes
   */
  static AccessibleName attach(Control control, String name, boolean described) {
    AccessibleName listener = new AccessibleName(name, described);
    control.getAccessible().addAccessibleListener(listener);
    if (described) {
      control.getAccessible().addAccessibleControlListener(GROUP_ROLE);
    }
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

  @Override
  public void getDescription(AccessibleEvent event) {
    if (described && event.childID == ACC.CHILDID_SELF) {
      event.result = name;
    }
  }
}
