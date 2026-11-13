package io.github.ajdailey.qlogg.ui.swt;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

/** The answers of {@link PlatformTraits} that GTK 3 and GTK 4 share. */
abstract class GtkTraits implements PlatformTraits {
  // Menu item icons.
  /** Side of the image a visible control is drawn into; the corner holds no item. */
  private static final int SAMPLE = 2;

  @Override
  public final String name() {
    return "gtk";
  }

  @Override
  public final String monospaceFamily() {
    return "Monospace";
  }

  @Override
  public final int monospaceSize() {
    return 10;
  }

  @Override
  public final boolean macKeys() {
    return false;
  }

  @Override
  public final boolean systemAppMenu() {
    return false;
  }

  @Override
  public final boolean positionsWindows() {
    return !"wayland".equals(System.getProperty(MainWindow.GDK_BACKEND));
  }

  @Override
  public final boolean remeasuresTrim() {
    return false;
  }

  @Override
  public final boolean launchesByExtension() {
    return false;
  }

  @Override
  public final boolean switchesThemeLive() {
    return true;
  }

  @Override
  public final boolean followsSystemAppearance() {
    return false;
  }

  @Override
  public final boolean themesAtCreation() {
    return false;
  }

  @Override
  public final boolean lightChecksUnderDark() {
    return false;
  }

  @Override
  public final DialogColors dialogColors(Display display) {
    return new NativeDialogColors();
  }

  // Menu item icons.

  @Override
  public final boolean padsMenuImages() {
    return true;
  }

  @Override
  public final boolean fixedMenuInk() {
    return false;
  }

  @Override
  public final boolean fadesDisabledIcons() {
    return true;
  }

  @Override
  public final Color shownBackground(Control source) {
    if (!source.isVisible()) {
      return source.getBackground();
    }
    Image image = new Image(source.getDisplay(), SAMPLE, SAMPLE);
    GC gc = new GC(image);
    try {
      if (!source.print(gc)) {
        return source.getBackground();
      }
      ImageData data = image.getImageData();
      RGB rgb = data.palette.getRGB(data.getPixel(SAMPLE - 1, SAMPLE - 1));
      return new Color(rgb);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }
}
