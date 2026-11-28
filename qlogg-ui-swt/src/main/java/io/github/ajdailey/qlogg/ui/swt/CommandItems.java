package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.MenuTable;
import io.github.ajdailey.qlogg.app.controller.CommandStates;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.MenuItem;

/**
 * The style, run rule and state of a command's item in a context menu, as the menu bar has them.
 */
final class CommandItems {
  private CommandItems() {}

  /**
   * Returns the item style of a command.
   *
   * @param command the command
   * @return {@link SWT#RADIO} for {@link MenuTable#radio}, {@link SWT#CHECK} for another {@link
   *     CommandId#check()} command, else {@link SWT#PUSH}
   */
  static int style(CommandId command) {
    if (MenuTable.radio(command)) {
      return SWT.RADIO;
    }
    return command.check() ? SWT.CHECK : SWT.PUSH;
  }

  /**
   * Reports whether a selection event on an item runs its command: SWT also sends one to the radio
   * item a choice clears.
   *
   * @param item the item the event came from
   * @return false for a radio item that is not selected
   */
  static boolean runs(MenuItem item) {
    return (item.getStyle() & SWT.RADIO) == 0 || item.getSelection();
  }

  /**
   * Enables an item from the command states and, for a check or radio item, sets its selection.
   *
   * @param item the item
   * @param command its command
   * @param states the states
   */
  static void sync(MenuItem item, CommandId command, CommandStates states) {
    item.setEnabled(states.enabled(command));
    if (command.check()) {
      item.setSelection(states.checked(command));
    }
  }
}
