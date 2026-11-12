package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.Keymap;
import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.LineViewController;
import io.github.ajdailey.qlogg.app.highlight.ColorLabel;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

/** Fills the Color Label cascade of the Highlighters menu and the log view context menu. */
final class ColorLabelMenu {
  private ColorLabelMenu() {}

  /**
   * Replaces a cascade's items: None, then each color label by name with its keys.
   *
   * @param menu the cascade
   * @param application the window's controller
   * @param keymap the keys the items show
   * @param view the view the items act on, focused first; null acts on the focused view
   */
  static void fill(
      Menu menu,
      final ApplicationController application,
      Keymap keymap,
      final LineViewController view) {
    for (MenuItem item : menu.getItems()) {
      item.dispose();
    }
    MenuItem none = new MenuItem(menu, SWT.PUSH);
    none.setText(UiText.MENU_NONE.text());
    none.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (view != null) {
              application.setFocusedView(view);
            }
            application.removeColorLabel();
          }
        });
    new MenuItem(menu, SWT.SEPARATOR);
    List<ColorLabel> labels = application.highlightSettings().labels();
    boolean mac = SwtKeys.mac();
    for (int i = 0; i < labels.size(); i++) {
      final int index = i;
      MenuItem item = new MenuItem(menu, SWT.PUSH);
      CommandId command = CommandId.values()[CommandId.COLOR_LABEL_1.ordinal() + i];
      String name = labels.get(i).name().replace("&", "&&");
      String keys = keymap.menuKeyText(command, mac);
      item.setText(keys.isEmpty() ? name : name + "\t" + keys);
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              if (view != null) {
                application.setFocusedView(view);
              }
              application.applyColorLabel(index);
            }
          });
    }
  }
}
