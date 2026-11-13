package io.github.ajdailey.qlogg.ui.swt;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

/** The win32 answers of {@link PlatformTraits}. */
final class Win32Traits implements PlatformTraits {
  @Override
  public String name() {
    return "win32";
  }

  @Override
  public String monospaceFamily() {
    return "Consolas";
  }

  @Override
  public int monospaceSize() {
    return 10;
  }

  @Override
  public boolean macKeys() {
    return false;
  }

  @Override
  public boolean systemAppMenu() {
    return false;
  }

  @Override
  public boolean positionsWindows() {
    return true;
  }

  @Override
  public boolean remeasuresTrim() {
    return true;
  }

  @Override
  public boolean launchesByExtension() {
    return true;
  }

  @Override
  public boolean opensBrowser() {
    return true;
  }

  @Override
  public boolean switchesThemeLive() {
    return false;
  }

  @Override
  public boolean followsSystemAppearance() {
    return false;
  }

  @Override
  public boolean themesAtCreation() {
    return true;
  }

  @Override
  public boolean lightChecksUnderDark() {
    return true;
  }

  @Override
  public DialogColors dialogColors(Display display) {
    return SwtThemeService.nativeDark(display)
        ? new Win32DialogColors(display)
        : new NativeDialogColors();
  }

  @Override
  public WindowStacking stacking() {
    return new Win32Stacking();
  }

  @Override
  public ToolItemSizing toolItemSizing() {
    return NativeToolItemSizing.INSTANCE;
  }

  // Menu item icons.

  @Override
  public boolean menuImages() {
    return true;
  }

  @Override
  public boolean padsMenuImages() {
    return false;
  }

  @Override
  public boolean fixedMenuInk() {
    return true;
  }

  @Override
  public boolean fadesDisabledIcons() {
    return false;
  }

  @Override
  public Color shownBackground(Control source) {
    return source.getBackground();
  }
}
