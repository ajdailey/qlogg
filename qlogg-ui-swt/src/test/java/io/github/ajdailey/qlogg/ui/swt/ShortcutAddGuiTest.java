package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.command.KeyStroke;
import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Keys posted to the Preferences shortcut capture field over a launcher-composed window: {@code
 * Ctrl+Shift+O} ({@code Cmd+Shift+O} on macOS), the Opened Files accelerator, reaches the field,
 * and Add shows the conflict.
 */
@Tag("swt")
class ShortcutAddGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(5);

  private static final int SHORTCUTS_TAB = 3;

  @TempDir Path temp;

  private Display display;
  private SlotWindows windows;
  private PreferencesDialog dialog;
  private final List<String> history = new ArrayList<String>();

  /** The state mask of the last posted letter's KeyDown. */
  private int letterMask;

  private final Listener recorder =
      new Listener() {
        @Override
        public void handleEvent(Event event) {
          if (event.keyCode == 'o') {
            letterMask = event.stateMask;
          }
          history.add(
              event.widget.getClass().getSimpleName()
                  + " "
                  + event.keyCode
                  + "/"
                  + Integer.toHexString(event.stateMask));
        }
      };

  @BeforeEach
  void open() throws IOException {
    display = new Display();
    windows = new SlotWindows(display, temp.resolve("config"), ThemeMode.LIGHT, null);
    windows.open(40, 40, 900, 600);
    windows.settle();
    display.addFilter(SWT.KeyDown, recorder);
  }

  @AfterEach
  void close() {
    display.removeFilter(SWT.KeyDown, recorder);
    try {
      if (dialog != null && !dialog.shell().isDisposed()) {
        dialog.shell().dispose();
      }
      windows.close();
      assertTrue(windows.failures.isEmpty(), windows.failures.toString());
    } finally {
      display.dispose();
    }
  }

  @Test
  void theOpenedFilesKeyReachesTheCaptureFieldAndAddNamesTheConflict() {
    dialog =
        new PreferencesDialog(windows.slots.get(0).window().shell(), windows.controllers.get(0));
    dialog.open();
    dialog.folder().select(SHORTCUTS_TAB);
    TableItem[] items = dialog.commands().getItems();
    for (int i = 0; i < items.length; i++) {
      if (items[i].getText(1).equals(CommandId.OPEN.title())) {
        dialog.commands().setSelection(i);
      }
    }
    dialog.commands().notifyListeners(SWT.Selection, new Event());
    final Text capture = dialog.capture();
    dialog.shell().forceActive();
    capture.setFocus();
    assumeTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return display.getFocusControl() == capture;
              }
            },
            BOUND),
        "the capture field takes the focus");
    post(SWT.F7);
    assumeTrue(captured(capture, "F7"), "posted keys reach the capture field; " + history);
    dialog.clearCaptured();
    history.clear();
    postWith(new int[] {SWT.MOD1, SWT.SHIFT}, 'o');
    String keys = KeyStroke.of(KeyStroke.PRIMARY, 'O').text(SwtKeys.mac());
    boolean reached = captured(capture, keys);
    // On a macOS x86_64 runner the posted letter arrived without one or both posted modifiers.
    assumeTrue(
        reached || (letterMask & (SWT.MOD1 | SWT.SHIFT)) == (SWT.MOD1 | SWT.SHIFT),
        "the posted modifiers reach the letter; " + history);
    assertTrue(reached, capture.getText() + "; " + history);
    dialog.addCaptured();
    assertTrue(
        dialog.message().getText().contains(CommandId.SWITCH_FILE.title()),
        dialog.message().getText());
    assertTrue(dialog.message().getSize().y > 0, "message height");
  }

  private boolean captured(final Text capture, final String text) {
    return EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return capture.getText().equals(text);
          }
        },
        BOUND);
  }

  private void post(int keyCode) {
    postKey(SWT.KeyDown, keyCode, '\0');
    postKey(SWT.KeyUp, keyCode, '\0');
  }

  /** Posts a character key with modifier keys held, each pressed in order and released after. */
  private void postWith(int[] modifiers, char key) {
    for (int modifier : modifiers) {
      postKey(SWT.KeyDown, modifier, '\0');
    }
    try {
      postKey(SWT.KeyDown, 0, key);
      postKey(SWT.KeyUp, 0, key);
    } finally {
      for (int i = modifiers.length - 1; i >= 0; i--) {
        postKey(SWT.KeyUp, modifiers[i], '\0');
      }
    }
  }

  private void postKey(int type, int keyCode, char character) {
    Event event = new Event();
    event.type = type;
    event.keyCode = keyCode;
    event.character = character;
    display.post(event);
  }
}
