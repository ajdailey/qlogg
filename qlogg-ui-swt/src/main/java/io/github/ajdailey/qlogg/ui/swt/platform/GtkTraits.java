package io.github.ajdailey.qlogg.ui.swt.platform;

import io.github.ajdailey.qlogg.app.view.UiDispatcher;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

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
  public final String toolkitVersion() {
    String version = System.getProperty(GTK_VERSION);
    return version == null ? "" : "GTK " + version;
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
  public final int busyCursor() {
    return SWT.CURSOR_APPSTARTING;
  }

  @Override
  public final DialogColors dialogColors(Display display) {
    return new NativeDialogColors();
  }

  @Override
  public final ChromeColors chromeColors(Display display) {
    return new NativeChromeColors();
  }

  @Override
  public final DialogTabs dialogTabs(Composite parent) {
    return new NativeDialogTabs(parent);
  }

  @Override
  public final ThemeFollow themeFollow(Display display) {
    return new GtkThemeFollow(display);
  }

  @Override
  public final FullScreen fullScreen(Shell shell, UiDispatcher ui) {
    return new NativeFullScreen(shell);
  }

  @Override
  public final LaunchDocuments launchDocuments() {
    return new NativeLaunchDocuments();
  }

  @Override
  public final AccessibleLabels accessibleLabels() {
    return new NativeAccessibleLabels();
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
