package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.controller.OverviewController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.app.view.DocumentViewListener;
import io.github.ajdailey.qlogg.app.view.DocumentViewState;
import io.github.ajdailey.qlogg.app.view.OverviewListener;
import io.github.ajdailey.qlogg.core.model.OverviewBins;
import io.github.ajdailey.qlogg.core.model.Rgb;
import io.github.ajdailey.qlogg.core.model.Viewport;
import io.github.ajdailey.qlogg.core.time.SpreadMode;
import io.github.ajdailey.qlogg.core.time.TimeSpread;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * The overview bar between the main view's text and its scroll bar: one pixel row per range of
 * rows, a mark line where the range holds marks and a match line, drawn over it, where it holds
 * matches. A line down the left edge separates it from the text, and a line across it marks the
 * first and the last visible line. Under them lies the time-spread layer: a row shaded by its
 * {@link TimeSpread} level, in the disorder color where its time runs backward. Painting reads the
 * controller's last {@link OverviewBins} and {@link TimeSpread} snapshots only. A click or a drag
 * shows the line under the pointer.
 */
final class OverviewBar {
  /** Width of the bar in pixels. */
  static final int WIDTH = 14;

  /** Alpha of each time-spread level from 1; a match or mark line stays readable over the last. */
  private static final int[] SPREAD_ALPHA = {56, 104, 152, 200};

  private final Canvas canvas;
  private final DocumentController document;
  private final OverviewController overview;
  private PaletteColors colors;
  private boolean dragging;

  // Accessible names.
  private final AccessibleName accessibleName;
  private final OverviewListener binsListener =
      new OverviewListener() {
        @Override
        public void binsChanged() {
          if (!canvas.isDisposed()) {
            canvas.redraw();
          }
        }
      };
  private final DocumentViewListener viewListener =
      new DocumentViewListener() {
        @Override
        public void stateChanged(DocumentViewState state) {
          // The bins follow the controller.
        }

        @Override
        public void contentChanged() {
          if (!canvas.isDisposed()) {
            canvas.redraw();
          }
        }
      };

  OverviewBar(Composite parent, DocumentController document, Palette palette) {
    this.document = document;
    this.overview = document.overview();
    canvas = new Canvas(parent, SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND);
    // Accessible names.
    accessibleName = AccessibleName.attach(canvas, UiText.ACCESSIBLE_OVERVIEW.text());
    colors = new PaletteColors(canvas.getDisplay(), palette);
    overview.addListener(binsListener);
    document.addListener(viewListener);
    canvas.addListener(
        SWT.Paint,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            paint(event.gc);
          }
        });
    canvas.addListener(
        SWT.Resize,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            geometry();
          }
        });
    canvas.addListener(
        SWT.MouseDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.button == 1) {
              dragging = true;
              overview.jumpTo(event.y);
            }
          }
        });
    canvas.addListener(
        SWT.MouseMove,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (dragging) {
              overview.jumpTo(Math.max(0, event.y));
            }
          }
        });
    canvas.addListener(
        SWT.MouseUp,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            dragging = false;
          }
        });
    canvas.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            overview.removeListener(binsListener);
            overview.barRemoved();
            document.removeListener(viewListener);
            colors.dispose();
          }
        });
  }

  Canvas control() {
    return canvas;
  }

  AccessibleName accessibleName() {
    return accessibleName;
  }

  /** Reports the bar's height and visibility to the controller. */
  void geometry() {
    if (!canvas.isDisposed()) {
      overview.setGeometry(canvas.getClientArea().height, canvas.getVisible());
    }
  }

  void setPalette(Palette palette) {
    if (canvas.isDisposed() || palette == colors.palette) {
      return;
    }
    PaletteColors old = colors;
    colors = new PaletteColors(canvas.getDisplay(), palette);
    old.dispose();
    canvas.redraw();
  }

  Palette palette() {
    return colors.palette;
  }

  /**
   * Returns the color of a time-spread level over the bar's background.
   *
   * @param palette the palette
   * @param mode the layer's mode
   * @param level 1 to {@link TimeSpread#MAX_LEVEL}
   * @return {@code 0xRRGGBB}
   */
  static int spreadColor(Palette palette, SpreadMode mode, int level) {
    Rgb color = mode == SpreadMode.PAUSES ? palette.spreadPause() : palette.spreadActivity();
    return PaletteColors.blend(
        SPREAD_ALPHA[level - 1] << 24 | PaletteColors.packed(color),
        PaletteColors.packed(palette.overview()));
  }

  private void paintSpread(GC gc, Rectangle area) {
    TimeSpread spread = overview.spread();
    int height = Math.min(area.height, spread.height());
    for (int y = 0; y < height; y++) {
      int level = spread.levels()[y];
      if (level != 0) {
        gc.setBackground(
            level == TimeSpread.DISORDER
                ? colors.disorder
                : colors.cached(spreadColor(colors.palette, spread.mode(), level)));
        gc.fillRectangle(1, y, area.width - 1, 1);
      }
    }
  }

  private void paint(GC gc) {
    Rectangle area = canvas.getClientArea();
    gc.setBackground(colors.overview);
    gc.fillRectangle(area);
    paintSpread(gc, area);
    OverviewBins bins = overview.bins();
    int height = Math.min(area.height, bins.height());
    for (int y = 0; y < height; y++) {
      if (bins.marks()[y] > 0) {
        gc.setForeground(colors.markMarker);
        gc.drawLine(3, y, area.width - 3, y);
      }
      if (bins.matches()[y] > 0) {
        gc.setForeground(colors.matchMarker);
        gc.drawLine(3, y, area.width - 3, y);
      }
    }
    gc.setForeground(colors.overviewFrame);
    gc.drawLine(0, 0, 0, area.height - 1);
    Viewport viewport = document.viewport();
    long count = viewport.lineCount();
    if (count > 0 && area.height > 0) {
      int top = OverviewBins.rowOf(viewport.topLine(), count, area.height);
      long last = Math.min(count - 1, viewport.topLine() + Math.max(0, viewport.rows() - 1));
      int bottom = OverviewBins.rowOf(last, count, area.height);
      gc.drawLine(1, top, area.width - 1, top);
      gc.drawLine(1, bottom, area.width - 1, bottom);
    }
  }
}
