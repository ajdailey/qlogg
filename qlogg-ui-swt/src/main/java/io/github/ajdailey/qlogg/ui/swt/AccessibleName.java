package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.ui.swt.platform.AccessibleLabels;
import io.github.ajdailey.qlogg.ui.swt.platform.PlatformTraits;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleControlAdapter;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.widgets.Control;

/**
 * The name a screen reader reads for a control, settable after the control is built. The platform's
 * {@link AccessibleLabels} adds the description and role its screen reader needs. A control drawn
 * in parts, such as the column header, names each part as an accessible child ({@link
 * #attach(Control, String, Parts)}).
 */
final class AccessibleName extends AccessibleAdapter {
  /** The parts of a control a screen reader reads as its children. */
  interface Parts {
    /**
     * Counts the parts.
     *
     * @return 0 or more
     */
    int count();

    /**
     * Returns a part's name.
     *
     * @param part 0 to {@link #count} - 1
     * @return the name
     */
    String name(int part);
  }

  private String name;
  private Parts parts;

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

  /**
   * Names a control and its parts: accessible child {@code i} is part {@code i}, in the role of a
   * column header.
   *
   * @param control the control
   * @param name the control's name
   * @param parts the parts
   * @return the listener, through which the control's name changes
   */
  static AccessibleName attach(Control control, String name, final Parts parts) {
    AccessibleName listener = attach(control, name);
    listener.parts = parts;
    control
        .getAccessible()
        .addAccessibleControlListener(
            new AccessibleControlAdapter() {
              @Override
              public void getChildCount(AccessibleControlEvent event) {
                event.detail = parts.count();
              }

              @Override
              public void getChildren(AccessibleControlEvent event) {
                Object[] children = new Object[parts.count()];
                for (int i = 0; i < children.length; i++) {
                  children[i] = Integer.valueOf(i);
                }
                event.children = children;
              }

              @Override
              public void getRole(AccessibleControlEvent event) {
                if (event.childID != ACC.CHILDID_SELF) {
                  event.detail = ACC.ROLE_TABLECOLUMNHEADER;
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
    } else if (parts != null && event.childID >= 0 && event.childID < parts.count()) {
      event.result = parts.name(event.childID);
    }
  }
}
