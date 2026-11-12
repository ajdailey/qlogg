package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.theme.Palette;
import io.github.ajdailey.qlogg.core.model.Rgb;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleControlAdapter;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolder2Adapter;
import org.eclipse.swt.custom.CTabFolderEvent;
import org.eclipse.swt.custom.CTabFolderRenderer;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ToolBar;

/**
 * Buttons at the right end of a {@code CTabFolder}'s strip, set as its top-right control: an
 * optional Open, the tab list and an optional Close. Each button is a {@code Canvas} painting a
 * {@link ToolIcons} image on the palette's {@code gutter}, the strip's background, in the ink that
 * color calls for, where a native tool bar would draw its own background. The list button shows the
 * count of hidden tabs and opens a {@link TabList}. The mouse wheel over the strip brings hidden
 * tabs into view without changing the selection.
 *
 * <p>SWT's chevron does not show: the renderer draws nothing for it and its {@code ToolBar} is held
 * at zero width, while the strip still reserves the chevron's width. A request for SWT's list
 * ({@code CTabFolder2Listener.showList}) opens this list instead.
 */
final class TabStripButtons {
  private static final int ICON = 16;
  private static final int PAD = 4;
  private static final int COUNT_GAP = 2;
  private static final int MAX_COUNT = 99;
  private static final int DISABLED_ALPHA = 100;
  private static final int HOT_PERCENT = 15;
  private static final String HOOKED = "qlogg.tabStrip.chevron";

  private final CTabFolder tabs;
  private final Canvas group;
  private final ToolIcons icons;
  private final TabList tabList;
  private final StripButton open;
  private final StripButton list;
  private final StripButton close;
  private Color text;
  private Color hot;
  private int hidden = -1;

  /** One painted button. */
  private final class StripButton {
    final Canvas canvas;
    final Runnable action;
    final String icon;
    String label = "";
    boolean inside;
    boolean pressed;

    StripButton(String icon, Runnable action) {
      this.icon = icon;
      this.action = action;
      canvas = new Canvas(group, SWT.NO_FOCUS | SWT.DOUBLE_BUFFERED);
      GridData data = new GridData(SWT.CENTER, SWT.CENTER, false, false);
      data.widthHint = ICON + 2 * PAD;
      data.heightHint = ICON + 2 * PAD;
      canvas.setLayoutData(data);
      Listener mouse =
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              mouse(event);
            }
          };
      for (int type :
          new int[] {SWT.Paint, SWT.MouseEnter, SWT.MouseExit, SWT.MouseDown, SWT.MouseUp}) {
        canvas.addListener(type, mouse);
      }
      canvas.addListener(SWT.MouseVerticalWheel, wheel());
      canvas
          .getAccessible()
          .addAccessibleListener(
              new AccessibleAdapter() {
                @Override
                public void getName(AccessibleEvent event) {
                  event.result = label;
                }
              });
      canvas
          .getAccessible()
          .addAccessibleControlListener(
              new AccessibleControlAdapter() {
                @Override
                public void getRole(AccessibleControlEvent event) {
                  event.detail = ACC.ROLE_PUSHBUTTON;
                }

                @Override
                public void getDefaultAction(AccessibleControlEvent event) {
                  event.result = label;
                }
              });
    }

    void setLabel(String value) {
      label = value;
      canvas.setToolTipText(value);
    }

    private void mouse(Event event) {
      switch (event.type) {
        case SWT.Paint -> paint(this, event.gc);
        case SWT.MouseEnter -> {
          inside = true;
          canvas.redraw();
        }
        case SWT.MouseExit -> {
          inside = false;
          pressed = false;
          canvas.redraw();
        }
        case SWT.MouseDown -> {
          pressed = event.button == 1;
          canvas.redraw();
        }
        case SWT.MouseUp -> {
          boolean click = pressed && event.button == 1 && inside(event);
          pressed = false;
          canvas.redraw();
          if (click && tabs.getItemCount() > 0) {
            action.run();
          }
        }
        default -> {}
      }
    }

    private boolean inside(Event event) {
      Point size = canvas.getSize();
      return event.x >= 0 && event.y >= 0 && event.x < size.x && event.y < size.y;
    }
  }

  /**
   * Adds the buttons to a folder's strip.
   *
   * @param tabs the folder; its top-right control and renderer are replaced
   * @param palette the palette the strip shows
   * @param openAction runs Open, or null for no Open button
   * @param closeAction closes the current tab, or null for no Close button
   */
  TabStripButtons(CTabFolder tabs, Palette palette, Runnable openAction, Runnable closeAction) {
    this.tabs = tabs;
    tabList = new TabList(tabs);
    group = new Canvas(tabs, SWT.NO_FOCUS);
    int columns = (openAction == null ? 0 : 1) + 1 + (closeAction == null ? 0 : 1);
    GridLayout layout = new GridLayout(columns, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    layout.horizontalSpacing = 0;
    group.setLayout(layout);
    group.setBackground(color(palette.gutter()));
    icons = new ToolIcons(group, group.getBackground());
    open = openAction == null ? null : new StripButton(ToolIcons.TAB_NEW, openAction);
    list =
        new StripButton(
            ToolIcons.TAB_LIST,
            new Runnable() {
              @Override
              public void run() {
                tabList.show(list.canvas);
              }
            });
    close = closeAction == null ? null : new StripButton(ToolIcons.TAB_CLOSE, closeAction);
    setPalette(palette);
    tabs.setRenderer(new ChevronlessRenderer(tabs));
    tabs.setTopRight(group, SWT.RIGHT);
    listen();
    update();
  }

  /**
   * Draws nothing for SWT's chevron, which keeps its size: on GTK 4 a 1 px chevron left the
   * selected last tab hidden after a new tab opened.
   */
  private static final class ChevronlessRenderer extends CTabFolderRenderer {
    ChevronlessRenderer(CTabFolder parent) {
      super(parent);
    }

    @Override
    protected void draw(int part, int state, Rectangle bounds, GC gc) {
      if (part != PART_CHEVRON_BUTTON) {
        super.draw(part, state, bounds, gc);
      }
    }
  }

  private void listen() {
    tabs.addListener(
        SWT.Paint,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            update();
          }
        });
    tabs.addListener(SWT.MouseVerticalWheel, wheel());
    tabs.addCTabFolder2Listener(
        new CTabFolder2Adapter() {
          @Override
          public void showList(CTabFolderEvent event) {
            event.doit = false;
            tabList.show(list.canvas);
          }
        });
  }

  private Listener wheel() {
    return new Listener() {
      @Override
      public void handleEvent(Event event) {
        if (event.count != 0) {
          event.doit = false;
          tabList.scroll(event.count > 0 ? -1 : 1);
        }
      }
    };
  }

  /**
   * Sets the strip colors: the palette's {@code gutter} background, the icon ink it calls for, and
   * the count in {@code text}.
   *
   * @param palette the palette
   */
  void setPalette(Palette palette) {
    if (group.isDisposed()) {
      return;
    }
    Rgb background = palette.gutter();
    Rgb ink = palette.text();
    group.setBackground(color(background));
    icons.follow(group.getBackground());
    // Creates the images now, so the set holds one image per button between paints.
    for (StripButton button : new StripButton[] {open, list, close}) {
      if (button != null) {
        icons.get(button.icon);
      }
    }
    for (Control child : group.getChildren()) {
      child.setBackground(group.getBackground());
    }
    text = color(ink);
    hot =
        new Color(
            mix(background.red(), ink.red()),
            mix(background.green(), ink.green()),
            mix(background.blue(), ink.blue()));
    redraw();
  }

  private static int mix(int from, int to) {
    return from + (to - from) * HOT_PERCENT / 100;
  }

  private static Color color(Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
  }

  /**
   * Sets the Open and Close tool tips and accessible names.
   *
   * @param openLabel the Open label
   * @param closeLabel the Close label
   */
  void setLabels(String openLabel, String closeLabel) {
    if (open != null) {
      open.setLabel(openLabel);
    }
    if (close != null) {
      close.setLabel(closeLabel);
    }
  }

  /**
   * Returns the button group, the folder's top-right control.
   *
   * @return the group
   */
  Control control() {
    return group;
  }

  /**
   * Returns the buttons in strip order.
   *
   * @return Open, list and Close, each present when created
   */
  List<Canvas> buttons() {
    List<Canvas> buttons = new ArrayList<Canvas>(3);
    for (StripButton button : new StripButton[] {open, list, close}) {
      if (button != null) {
        buttons.add(button.canvas);
      }
    }
    return buttons;
  }

  /**
   * Returns the list button.
   *
   * @return the button
   */
  Canvas listButton() {
    return list.canvas;
  }

  /**
   * Returns the hidden count the list button shows.
   *
   * @return the count
   */
  int hiddenCount() {
    return Math.max(0, hidden);
  }

  /**
   * Reports whether the icons use the light ink, for a dark strip.
   *
   * @return true for the light ink
   */
  boolean lightInk() {
    return icons.darkBackground();
  }

  /**
   * Returns the tab list the list button opens.
   *
   * @return the list
   */
  TabList tabList() {
    return tabList;
  }

  private void redraw() {
    for (Control child : group.getChildren()) {
      child.redraw();
    }
  }

  /**
   * Reads the hidden count after the strip lays out; a count of other width resizes the list
   * button, and the folder lays out its controls again.
   */
  private void update() {
    if (group.isDisposed()) {
      return;
    }
    hideChevron();
    tabList.showSelection();
    int count = 0;
    for (CTabItem item : tabs.getItems()) {
      if (!item.isShowing()) {
        count++;
      }
    }
    if (count == hidden) {
      return;
    }
    hidden = count;
    list.setLabel(count == 0 ? UiText.TAB_LIST.text() : UiText.TAB_LIST_HIDDEN.format(count));
    GridData data = (GridData) list.canvas.getLayoutData();
    int width = ICON + 2 * PAD + (count == 0 ? 0 : COUNT_GAP + countWidth());
    list.canvas.redraw();
    if (data.widthHint != width) {
      data.widthHint = width;
      group.setSize(group.computeSize(SWT.DEFAULT, SWT.DEFAULT));
      group.layout(true);
    }
  }

  private int countWidth() {
    GC gc = new GC(list.canvas);
    try {
      return gc.textExtent(countText()).x;
    } finally {
      gc.dispose();
    }
  }

  private String countText() {
    return hidden > MAX_COUNT ? MAX_COUNT + "+" : Integer.toString(hidden);
  }

  /**
   * Holds SWT's chevron tool bar at zero width, which GTK hides, so no second list control shows or
   * takes a click. The folder sets its bounds again on each layout.
   */
  private void hideChevron() {
    for (Control child : tabs.getChildren()) {
      if (child instanceof ToolBar bar && bar.getData(HOOKED) == null) {
        bar.setData(HOOKED, Boolean.TRUE);
        bar.setToolTipText(UiText.TAB_LIST.text());
        bar.addListener(
            SWT.Resize,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                Point size = bar.getSize();
                if (size.x > 0) {
                  bar.setSize(0, size.y);
                }
              }
            });
        Point size = bar.getSize();
        bar.setSize(0, size.y);
      }
    }
  }

  private void paint(StripButton button, GC gc) {
    Point size = button.canvas.getSize();
    gc.setBackground(group.getBackground());
    gc.fillRectangle(0, 0, size.x, size.y);
    boolean enabled = tabs.getItemCount() > 0;
    if (enabled && hot != null && (button.inside || button.pressed)) {
      gc.setBackground(hot);
      gc.fillRoundRectangle(0, 0, size.x, size.y, PAD * 2, PAD * 2);
    }
    if (!enabled) {
      gc.setAlpha(DISABLED_ALPHA);
    }
    Image image = icons.get(button.icon);
    if (image != null) {
      gc.drawImage(image, PAD, (size.y - ICON) / 2);
    }
    if (button == list && hidden > 0 && text != null) {
      gc.setForeground(text);
      String count = countText();
      Point extent = gc.textExtent(count);
      gc.drawString(count, PAD + ICON + COUNT_GAP, (size.y - extent.y) / 2, true);
    }
  }
}
