package io.github.ajdailey.qlogg.ui.swt.platform;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import org.eclipse.swt.widgets.ToolItem;

/**
 * GTK 4: turns off shrinking on a tool item's GtkPicture through SWT internals ({@code
 * ToolItem.imageHandle}, {@code GTK4.gtk_picture_set_can_shrink}), the one Public SWT API exception
 * for icons. SWT 3.135.0 wraps the picture in a box that measures 0 high for any width under GTK
 * 4.14, so the tool bar lays an image-only item out at its padding, 12 px, and shows no icon.
 * Without either internal, the items keep that layout.
 */
final class Gtk4ToolItemSizing implements ToolItemSizing {
  private final Field picture;
  private final Method canShrink;

  private Gtk4ToolItemSizing(Field picture, Method canShrink) {
    this.picture = picture;
    this.canShrink = canShrink;
  }

  /**
   * Finds the internals.
   *
   * @return the sizing, or one that changes nothing when SWT lacks either internal
   */
  static ToolItemSizing create() {
    try {
      Field picture = ToolItem.class.getDeclaredField("imageHandle");
      picture.setAccessible(true);
      Method canShrink =
          Class.forName("org.eclipse.swt.internal.gtk4.GTK4")
              .getMethod("gtk_picture_set_can_shrink", long.class, boolean.class);
      return new Gtk4ToolItemSizing(picture, canShrink);
    } catch (ReflectiveOperationException | SecurityException | InaccessibleObjectException e) {
      return NativeToolItemSizing.INSTANCE;
    }
  }

  @Override
  public void keepIconSize(ToolItem item) {
    try {
      long handle = picture.getLong(item);
      if (handle != 0) {
        canShrink.invoke(null, handle, false);
      }
    } catch (ReflectiveOperationException | IllegalArgumentException e) {
      // The item keeps SWT's layout.
    }
  }
}
