package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.app.theme.ThemeMode;
import io.github.ajdailey.qlogg.ui.swt.platform.ChromeColors;
import io.github.ajdailey.qlogg.ui.swt.platform.PlatformTraits;
import io.github.ajdailey.qlogg.ui.swt.platform.ThemeFollow;
import io.github.ajdailey.qlogg.ui.swt.platform.WindowRebuild;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.RowData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * On win32, from screen pixels: a text field holding {@code 0}, as a context count field does, in a
 * window built light under {@link ChromeColors}, unfocused behind an open modal dialog, through
 * alternating live switches with no rebuild (the window waits for its dialog). Under dark it reads
 * {@code #2b2b2b}; under light the system list background, never {@code #212121}, the color of the
 * dark theme SWT gives a field colored under the dark setting (measured on {@code windows-2025}).
 */
@Tag("swt")
class Win32ContextFieldTest {
  private static final Duration BOUND = Duration.ofSeconds(5);
  private static final RGB FIELD = new RGB(0x2b, 0x2b, 0x2b);
  private static final RGB THEMED = new RGB(0x21, 0x21, 0x21);
  private static final int ROUND_TRIPS = 3;

  private Display display;

  @AfterEach
  void close() {
    if (display != null) {
      display.setDarkThemePreferred(false);
      display.dispose();
    }
  }

  @Test
  void aWaitingLightWindowsContextFieldReadsTheSideAfterRoundTrips() {
    assumeTrue("win32".equals(SWT.getPlatform()), "win32 text field theme");
    display = new Display();
    SwtThemeService.open(display, ThemeMode.LIGHT);
    Shell window = new Shell(display, SWT.SHELL_TRIM);
    window.setLayout(new RowLayout());
    Composite row = new Composite(window, SWT.NONE);
    row.setLayout(new RowLayout());
    Text field = new Text(row, SWT.SINGLE | SWT.BORDER | SWT.RIGHT);
    field.setText("0");
    field.setLayoutData(new RowData(80, SWT.DEFAULT));
    ChromeColors chrome = PlatformTraits.PLATFORM.chromeColors(display);
    chrome.add(row);
    chrome.apply(false);
    window.setBounds(20, 20, 300, 120);
    window.open();
    Shell dialog = new Shell(window, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
    dialog.setBounds(400, 20, 200, 100);
    dialog.open();
    settle();
    ThemeFollow follow = PlatformTraits.PLATFORM.themeFollow(display);
    RGB light = display.getSystemColor(SWT.COLOR_LIST_BACKGROUND).getRGB();
    for (int i = 0; i < ROUND_TRIPS; i++) {
      for (boolean dark : new boolean[] {true, false}) {
        follow.showOnWindows(dark, WindowRebuild.NONE);
        chrome.apply(dark);
        settle();
        RGB ground = ground(field);
        String step = "round trip " + i + (dark ? ", dark" : ", light");
        assertNotEquals(THEMED, ground, step);
        assertEquals(dark ? FIELD : light, ground, step);
      }
    }
    assertTrue(dialog.isVisible(), "the modal dialog stays open");
  }

  /** Reads the screen inside the field's frame and returns its most frequent color. */
  private RGB ground(Text field) {
    Rectangle area = field.getClientArea();
    Point origin = field.toDisplay(area.x, area.y);
    Image image = new Image(display, Math.max(2, area.width), Math.max(2, area.height));
    GC gc = new GC(display);
    try {
      gc.copyArea(image, origin.x, origin.y);
      ImageData data = Pixels.read(image, field);
      return Pixels.mode(data);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  private void settle() {
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !display.readAndDispatch();
          }
        },
        BOUND);
    display.update();
  }
}
