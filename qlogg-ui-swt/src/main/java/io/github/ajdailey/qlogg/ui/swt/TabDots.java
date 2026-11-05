package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.controller.TabDataState;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.core.model.Rgb;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageDataProvider;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.widgets.Display;

/**
 * The tab dot images of one {@link Palette}: a filled circle in the state's color with a darker
 * rim, on a transparent square, rendered at each zoom the toolkit asks for. The owner disposes them
 * after its tabs take the next palette's images.
 */
final class TabDots {
  /** Image width and height at 100% zoom. */
  static final int SIZE = 10;

  /** Subsamples per pixel along each axis. */
  private static final int SAMPLES = 4;

  /** Share of the fill color kept in the rim, in percent. */
  private static final int RIM_PERCENT = 60;

  private final Image newLines;
  private final Image newMatches;

  /**
   * Creates the images.
   *
   * @param display the Display
   * @param palette the colors
   */
  TabDots(Display display, Palette palette) {
    newLines = image(display, palette.newLinesDot());
    newMatches = image(display, palette.newMatchesDot());
  }

  /**
   * Returns a state's image.
   *
   * @param state the tab's state
   * @return the image, or null for {@code NONE}
   */
  Image image(TabDataState state) {
    return switch (state) {
      case NONE -> null;
      case NEW_LINES -> newLines;
      case NEW_MATCHES -> newMatches;
    };
  }

  void dispose() {
    newLines.dispose();
    newMatches.dispose();
  }

  private static Image image(Display display, final Rgb color) {
    return new Image(
        display,
        new ImageDataProvider() {
          @Override
          public ImageData getImageData(int zoom) {
            return render(color, Math.max(1, SIZE * zoom / 100));
          }
        });
  }

  /**
   * Renders the dot with anti-aliased edges.
   *
   * @param color the fill color
   * @param size width and height in pixels
   * @return 24-bit data with an alpha channel
   */
  static ImageData render(Rgb color, int size) {
    ImageData data = new ImageData(size, size, 24, new PaletteData(0xff0000, 0xff00, 0xff));
    int rimRgb =
        (color.red() * RIM_PERCENT / 100) << 16
            | (color.green() * RIM_PERCENT / 100) << 8
            | color.blue() * RIM_PERCENT / 100;
    int fillRgb = color.red() << 16 | color.green() << 8 | color.blue();
    double center = size / 2.0;
    double outer = size * 0.4;
    double inner = outer - Math.max(1.0, size / (double) SIZE);
    int total = SAMPLES * SAMPLES;
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        int fills = 0;
        int rims = 0;
        for (int sy = 0; sy < SAMPLES; sy++) {
          for (int sx = 0; sx < SAMPLES; sx++) {
            double dx = x + (sx + 0.5) / SAMPLES - center;
            double dy = y + (sy + 0.5) / SAMPLES - center;
            double distance = Math.sqrt(dx * dx + dy * dy);
            if (distance <= inner) {
              fills++;
            } else if (distance <= outer) {
              rims++;
            }
          }
        }
        int covered = fills + rims;
        if (covered == 0) {
          continue;
        }
        data.setPixel(x, y, mix(fillRgb, fills, rimRgb, rims));
        data.setAlpha(x, y, covered * 255 / total);
      }
    }
    return data;
  }

  private static int mix(int first, int firstWeight, int second, int secondWeight) {
    int result = 0;
    int weight = firstWeight + secondWeight;
    for (int shift = 0; shift <= 16; shift += 8) {
      int a = (first >> shift) & 0xff;
      int b = (second >> shift) & 0xff;
      result |= ((a * firstWeight + b * secondWeight) / weight) << shift;
    }
    return result;
  }
}
