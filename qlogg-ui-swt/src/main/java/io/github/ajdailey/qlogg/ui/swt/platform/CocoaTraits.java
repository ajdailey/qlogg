package io.github.ajdailey.qlogg.ui.swt.platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

/** The cocoa answers of {@link PlatformTraits}. */
final class CocoaTraits implements PlatformTraits {
  @Override
  public String name() {
    return "cocoa";
  }

  @Override
  public String monospaceFamily() {
    return "Menlo";
  }

  @Override
  public int monospaceSize() {
    return 12;
  }

  @Override
  public boolean macKeys() {
    return true;
  }

  @Override
  public boolean systemAppMenu() {
    return true;
  }

  @Override
  public boolean positionsWindows() {
    return true;
  }

  @Override
  public boolean remeasuresTrim() {
    return false;
  }

  @Override
  public boolean launchesByExtension() {
    return false;
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
    return true;
  }

  @Override
  public boolean themesAtCreation() {
    return false;
  }

  @Override
  public boolean lightChecksUnderDark() {
    return false;
  }

  @Override
  public int busyCursor() {
    return SWT.CURSOR_WAIT;
  }

  @Override
  public DialogColors dialogColors(Display display) {
    return new NativeDialogColors();
  }

  @Override
  public WindowStacking stacking() {
    return new CocoaStacking();
  }

  @Override
  public ToolItemSizing toolItemSizing() {
    return NativeToolItemSizing.INSTANCE;
  }

  // Menu item icons.

  @Override
  public boolean menuImages() {
    return false;
  }

  @Override
  public boolean padsMenuImages() {
    return false;
  }

  @Override
  public boolean fixedMenuInk() {
    return false;
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
