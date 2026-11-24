package io.github.ajdailey.qlogg.ui.swt;

import java.util.HashMap;
import java.util.Map;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;

/**
 * Reads an image a test drew or captured into, in logical coordinates at any monitor zoom, and the
 * color statistics the color tests assert on. The read takes the image at the control's monitor
 * zoom ({@code Image.getImageData(int)}), where the drawn pixels are exact, and samples each
 * logical pixel at the first device pixel of its block. On cocoa {@code Image.getImageData()}
 * redraws a 200 % image at 100 % with interpolation, which blends fills at their edges and glyphs
 * into colors no assertion expects.
 */
final class Pixels {
  private Pixels() {}

  /**
   * Returns an image's pixels, one per logical pixel.
   *
   * @param image the image, sized in logical pixels
   * @param on the control whose monitor sets the zoom
   * @return the pixels, as wide and high as {@link Image#getBounds}
   */
  static ImageData read(Image image, Control on) {
    int zoom = on.getMonitor().getZoom();
    if (zoom == 100) {
      return image.getImageData();
    }
    Rectangle bounds = image.getBounds();
    ImageData device = image.getImageData(zoom);
    if (device.width == bounds.width && device.height == bounds.height) {
      return device;
    }
    ImageData logical = new ImageData(bounds.width, bounds.height, device.depth, device.palette);
    for (int y = 0; y < bounds.height; y++) {
      int sourceY = (int) ((long) y * device.height / bounds.height);
      for (int x = 0; x < bounds.width; x++) {
        logical.setPixel(
            x, y, device.getPixel((int) ((long) x * device.width / bounds.width), sourceY));
      }
    }
    return logical;
  }

  /** Returns the most frequent color. */
  static RGB mode(ImageData data) {
    return mode(data, new Rectangle(0, 0, data.width, data.height));
  }

  /** Returns the most frequent color inside an area. */
  static RGB mode(ImageData data, Rectangle area) {
    Map<Integer, Integer> counts = new HashMap<Integer, Integer>();
    int best = 0;
    int bestCount = -1;
    for (int y = Math.max(0, area.y); y < Math.min(data.height, area.y + area.height); y++) {
      for (int x = Math.max(0, area.x); x < Math.min(data.width, area.x + area.width); x++) {
        int pixel = data.getPixel(x, y);
        Integer count = counts.get(pixel);
        int next = count == null ? 1 : count + 1;
        counts.put(pixel, next);
        if (next > bestCount) {
          best = pixel;
          bestCount = next;
        }
      }
    }
    return data.palette.getRGB(best);
  }

  /** Returns the color of highest WCAG contrast against a ground. */
  static RGB strongest(ImageData data, RGB ground) {
    RGB best = ground;
    double max = 1;
    for (int y = 0; y < data.height; y++) {
      for (int x = 0; x < data.width; x++) {
        RGB rgb = data.palette.getRGB(data.getPixel(x, y));
        double value = contrast(rgb, ground);
        if (value > max) {
          max = value;
          best = rgb;
        }
      }
    }
    return best;
  }

  /** Returns the WCAG contrast of two colors. */
  static double contrast(RGB a, RGB b) {
    double la = luminance(a);
    double lb = luminance(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
  }

  /** Returns a color's WCAG relative luminance. */
  static double luminance(RGB rgb) {
    return 0.2126 * channelLuminance(rgb.red)
        + 0.7152 * channelLuminance(rgb.green)
        + 0.0722 * channelLuminance(rgb.blue);
  }

  private static double channelLuminance(int value) {
    double c = value / 255.0;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }
}
