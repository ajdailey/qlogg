package io.github.ajdailey.qlogg.ui.swt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/** Screen captures of a combo's closed field and open drop-down list, in logical pixels. */
final class ComboCapture {
  /**
   * 4.5:1 less a rounding margin: the native selection {@code #0078d7} under white text measures
   * 4.499:1.
   */
  static final double READABLE = 4.45;

  /** Identical captures in a row that count the list as drawn. */
  private static final int STABLE = 5;

  private static final Duration OPEN_BOUND = Duration.ofSeconds(5);

  private final Display display;
  private final Path directory;

  ComboCapture(Display display, Path directory) {
    this.display = display;
    this.directory = directory;
  }

  /** Returns the combo's closed field in display coordinates. */
  Rectangle field(Combo combo) {
    return display.map(combo.getParent(), null, combo.getBounds());
  }

  /**
   * Returns the area under the closed field that an open list of {@code rows} rows covers: the
   * field's width, from its bottom edge.
   */
  Rectangle below(Combo combo, int rows) {
    Rectangle field = field(combo);
    return new Rectangle(
        field.x, field.y + field.height, field.width, rows * combo.getItemHeight() + 8);
  }

  /**
   * Opens the combo's list and returns the area under the field once the list has drawn: the combo
   * reports the list visible and {@link #drawn} holds. The caller closes the list.
   *
   * @return the capture, or null if the list was not seen within 5 s
   */
  ImageData openList(final Combo combo, Rectangle area) {
    ImageData closed = capture(combo, area);
    combo.setListVisible(true);
    return drawn(
        combo,
        area,
        closed,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return combo.getListVisible();
          }
        });
  }

  /**
   * Returns the area once it differs from {@code before} and {@link #STABLE} captures in a row are
   * identical, while {@code ready} holds: a new shell or list has finished drawing.
   *
   * @return the capture, or null if that does not happen within 5 s
   */
  ImageData drawn(
      final Combo combo,
      final Rectangle area,
      final ImageData before,
      final BooleanSupplier ready) {
    final ImageData[] last = {before};
    final int[] same = {0};
    boolean seen =
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                if (!ready.getAsBoolean()) {
                  return false;
                }
                ImageData now = capture(combo, area);
                same[0] = equal(now, last[0]) ? same[0] + 1 : 0;
                last[0] = now;
                return same[0] >= STABLE && !equal(now, before);
              }
            },
            OPEN_BOUND);
    return seen ? last[0] : null;
  }

  /**
   * Opens a shell holding the combo and returns the combo's closed field once {@link #drawn} holds,
   * or null.
   */
  ImageData open(Shell shell, Combo combo) {
    shell.layout(true, true);
    Rectangle field = field(combo);
    ImageData before = capture(combo, field);
    shell.open();
    shell.forceActive();
    combo.setFocus();
    return drawn(
        combo,
        field,
        before,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return true;
          }
        });
  }

  /** Captures a screen area, one pixel per logical pixel of the combo's monitor. */
  ImageData capture(Combo combo, Rectangle area) {
    Image image = new Image(display, Math.max(1, area.width), Math.max(1, area.height));
    GC gc = new GC(display);
    try {
      gc.copyArea(image, area.x, area.y);
      return Pixels.read(image, combo);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  /** Saves a capture as {@code <name>.png}. */
  void save(String name, ImageData data) {
    ImageLoader loader = new ImageLoader();
    loader.data = new ImageData[] {data};
    try {
      Files.createDirectories(directory);
      loader.save(directory.resolve(name + ".png").toString(), SWT.IMAGE_PNG);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Returns the pixels of an area of a capture. */
  static ImageData crop(ImageData data, Rectangle area) {
    int width = Math.max(1, Math.min(area.width, data.width - area.x));
    int height = Math.max(1, Math.min(area.height, data.height - area.y));
    ImageData result = new ImageData(width, height, data.depth, data.palette);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        result.setPixel(x, y, data.getPixel(area.x + x, area.y + y));
      }
    }
    return result;
  }

  /**
   * Returns a row of a list captured from {@link #below}: clear of the list border and the scroll
   * bar.
   */
  static ImageData row(ImageData list, int height, int index) {
    return crop(list, new Rectangle(3, 2 + index * height, list.width - 24, height - 2));
  }

  /** Returns the contrast of the strongest color in a row against its most frequent one. */
  static double contrast(ImageData row) {
    RGB ground = Pixels.mode(row);
    return Pixels.contrast(ground, Pixels.strongest(row, ground));
  }

  /** Describes a row as its ground, its ink and their contrast. */
  static String describe(ImageData row) {
    RGB ground = Pixels.mode(row);
    RGB ink = Pixels.strongest(row, ground);
    return String.format(
        "ground %s ink %s contrast %.2f", ground, ink, Pixels.contrast(ground, ink));
  }

  private static boolean equal(ImageData a, ImageData b) {
    return a.width == b.width && a.height == b.height && Arrays.equals(a.data, b.data);
  }
}
