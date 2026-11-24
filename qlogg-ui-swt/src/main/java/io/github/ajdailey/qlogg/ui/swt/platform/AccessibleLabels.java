package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.accessibility.AccessibleListener;
import org.eclipse.swt.widgets.Control;

/**
 * How a screen reader takes a custom control's label, one implementation per platform from {@link
 * PlatformTraits#accessibleLabels}.
 */
public interface AccessibleLabels {
  /**
   * Gives a named control the description and role the platform's screen reader needs. VoiceOver
   * reads a {@code Canvas} without a role as {@code SWTComposite} with a blank description and does
   * not read the accessible name, so on cocoa the control also reports its name as its description
   * and {@code ACC.ROLE_GROUP} as its role; elsewhere nothing is added.
   *
   * @param control the control
   * @param description answers {@code getDescription} with the name
   */
  void describe(Control control, AccessibleListener description);
}
