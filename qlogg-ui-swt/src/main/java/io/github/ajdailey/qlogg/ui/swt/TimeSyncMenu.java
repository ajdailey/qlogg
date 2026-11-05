package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.controller.TimeSyncGroups;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

/** Fills the Time Sync cascade of the tab and log-view context menus. */
final class TimeSyncMenu {
  private TimeSyncMenu() {}

  /**
   * Replaces a cascade's items: a check item per other tab with a format, then Leave Time Sync.
   *
   * @param menu the cascade
   * @param groups the time sync groups
   * @param tab the tab the cascade acts on
   */
  static void fill(Menu menu, final TimeSyncGroups groups, final DocumentController tab) {
    for (MenuItem old : menu.getItems()) {
      old.dispose();
    }
    List<TimeSyncGroups.Entry> entries = groups.entries(tab);
    if (entries.isEmpty()) {
      MenuItem none = new MenuItem(menu, SWT.PUSH);
      none.setText(UiText.TIME_SYNC_NO_TABS.text());
      none.setEnabled(false);
    }
    for (final TimeSyncGroups.Entry entry : entries) {
      MenuItem item = new MenuItem(menu, SWT.CHECK);
      item.setText(entry.label().replace("&", "&&"));
      item.setSelection(entry.checked());
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              groups.toggle(tab, entry.document());
            }
          });
    }
    new MenuItem(menu, SWT.SEPARATOR);
    MenuItem leave = new MenuItem(menu, SWT.PUSH);
    leave.setText(UiText.TIME_SYNC_LEAVE.text());
    leave.setEnabled(groups.number(tab) > 0);
    leave.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            groups.leave(tab);
          }
        });
  }
}
