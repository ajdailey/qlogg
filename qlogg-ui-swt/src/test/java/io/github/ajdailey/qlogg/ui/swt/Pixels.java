package io.github.ajdailey.qlogg.ui.swt;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;

/**
 * Reads an image a test drew or captured into, in logical coordinates at any monitor zoom. The read
 * takes the image at the control's monitor zoom ({@code Image.getImageData(int)}), where the drawn
 * pixels are exact, and samples each logical pixel at the first device pixel of its block. On cocoa
 * {@code Image.getImageData()} redraws a 200 % image at 100 % with interpolation, which blends
 * fills at their edges and glyphs into colors no assertion expects.
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
}
