package io.github.ajdailey.qlogg.ui.swt;

import org.eclipse.swt.widgets.Display;

/** The answers of {@link PlatformTraits} that GTK 3 and GTK 4 share. */
abstract class GtkTraits implements PlatformTraits {
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
}
