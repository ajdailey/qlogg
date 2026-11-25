package io.github.ajdailey.qlogg.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.ui.swt.EventLoop;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.TableEditor;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link Win32DialogColors} and {@link NativeDialogColors} on any platform: which controls take the
 * dark dialog colors when the dialog shows, which keep their own, and controls added after it
 * opens. The drawn result on win32 is {@code Win32DarkDialogsTest}.
 */
@Tag("swt")
class DialogColorsTest {
  private static final Duration BOUND = Duration.ofSeconds(10);
  private static final RGB OWN = new RGB(0xc0, 0x10, 0x10);

  private Display display;
  private Shell owner;

  @BeforeEach
  void open() {
    display = new Display();
    display.setData(DialogColors.NATIVE_DARK, Boolean.TRUE);
    owner = new Shell(display);
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  @Test
  void win32ColorsSurfacesFieldsAndText() {
    Win32DialogColors colors = Win32DialogColors.INSTANCE;
    Shell shell = new Shell(owner, SWT.DIALOG_TRIM);
    colors.apply(shell);
    assertTrue(colors.colored(shell));
    Composite page = new Composite(shell, SWT.NONE);
    Label label = new Label(page, SWT.NONE);
    Link link = new Link(page, SWT.NONE);
    Button check = new Button(page, SWT.CHECK);
    Text text = new Text(page, SWT.BORDER);
    Table table = new Table(page, SWT.BORDER);
    Button push = new Button(page, SWT.PUSH);
    Combo readOnly = new Combo(page, SWT.READ_ONLY);
    Label own = new Label(page, SWT.NONE);
    own.setForeground(new Color(OWN));
    RGB pushBackground = push.getBackground().getRGB();
    RGB pushForeground = push.getForeground().getRGB();
    shell.open();
    assertEquals(Win32DialogColors.BODY.getRGB(), shell.getBackground().getRGB());
    assertEquals(Win32DialogColors.BODY.getRGB(), page.getBackground().getRGB());
    assertEquals(Win32DialogColors.TEXT.getRGB(), label.getForeground().getRGB());
    assertEquals(Win32DialogColors.TEXT.getRGB(), link.getForeground().getRGB());
    assertEquals(Win32DialogColors.link(display).getRGB(), link.getLinkForeground().getRGB());
    assertTrue(
        Contrast.ratio(link.getLinkForeground().getRGB(), Win32DialogColors.BODY.getRGB())
            >= Contrast.LINK);
    assertEquals(Win32DialogColors.TEXT.getRGB(), check.getForeground().getRGB());
    assertEquals(Win32DialogColors.FIELD.getRGB(), text.getBackground().getRGB());
    assertEquals(Win32DialogColors.TEXT.getRGB(), text.getForeground().getRGB());
    assertEquals(Win32DialogColors.FIELD.getRGB(), table.getBackground().getRGB());
    assertEquals(Win32DialogColors.FIELD.getRGB(), table.getHeaderBackground().getRGB());
    assertEquals(Win32DialogColors.TEXT.getRGB(), table.getHeaderForeground().getRGB());
    // The dark start draws push buttons dark already.
    assertEquals(pushBackground, push.getBackground().getRGB());
    assertEquals(pushForeground, push.getForeground().getRGB());
    // A read-only combo's list draws white unless the combo has colors; it takes the button face.
    assertEquals(Win32DialogColors.FACE.getRGB(), readOnly.getBackground().getRGB());
    assertEquals(Win32DialogColors.TEXT.getRGB(), readOnly.getForeground().getRGB());
    assertEquals(OWN, own.getForeground().getRGB());
    assertEquals(Win32DialogColors.BODY.getRGB(), colors.background(owner).getRGB());
  }

  @Test
  void win32ColorsAControlAddedAfterTheDialogOpens() {
    Win32DialogColors colors = Win32DialogColors.INSTANCE;
    Shell shell = new Shell(owner, SWT.DIALOG_TRIM);
    colors.apply(shell);
    shell.open();
    final Text later = new Text(shell, SWT.BORDER);
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return later.getBackground().getRGB().equals(Win32DialogColors.FIELD.getRGB());
              }
            },
            BOUND));
    assertEquals(Win32DialogColors.TEXT.getRGB(), later.getForeground().getRGB());
  }

  /**
   * Every field kind the dialogs create: single, search, read-only and multi-line text, a spinner,
   * and an in-place editor opened over a table row after the dialog shows, as Predefined Filters
   * and Marks open theirs.
   */
  @Test
  void win32ColorsEveryFieldKindIncludingAnInPlaceEditor() {
    Win32DialogColors colors = Win32DialogColors.INSTANCE;
    Shell shell = new Shell(owner, SWT.DIALOG_TRIM);
    colors.apply(shell);
    List<Control> fields =
        List.of(
            new Text(shell, SWT.BORDER | SWT.SINGLE),
            new Text(shell, SWT.SEARCH | SWT.ICON_CANCEL | SWT.BORDER),
            new Text(shell, SWT.BORDER | SWT.READ_ONLY),
            new Text(shell, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL),
            new Spinner(shell, SWT.BORDER));
    Table table = new Table(shell, SWT.BORDER);
    TableItem row = new TableItem(table, SWT.NONE);
    row.setText("mock");
    shell.open();
    for (Control field : fields) {
      assertEquals(Win32DialogColors.FIELD.getRGB(), field.getBackground().getRGB(), "" + field);
      assertEquals(Win32DialogColors.TEXT.getRGB(), field.getForeground().getRGB(), "" + field);
    }
    final Text editor = new Text(table, SWT.SINGLE);
    TableEditor place = new TableEditor(table);
    place.grabHorizontal = true;
    place.setEditor(editor, row, 0);
    try {
      assertTrue(
          EventLoop.runUntil(
              display,
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return editor.getBackground().getRGB().equals(Win32DialogColors.FIELD.getRGB());
                }
              },
              BOUND),
          "in-place editor");
      assertEquals(Win32DialogColors.TEXT.getRGB(), editor.getForeground().getRGB());
    } finally {
      // GTK fails disposing a table whose child field holds focus.
      editor.dispose();
      place.dispose();
    }
  }

  /**
   * An empty single-line field, read-only or editable, is filled with the field color and its hint
   * drawn in {@code HINT}, 4.5:1 or more on it; a field with no hint is filled too. A field holding
   * text and a multi-line field are left to the native drawing.
   */
  @Test
  void win32DrawsAnEmptyFieldsHintReadably() {
    Win32DialogColors colors = Win32DialogColors.INSTANCE;
    Shell shell = new Shell(owner, SWT.DIALOG_TRIM);
    colors.apply(shell);
    Text readOnly = new Text(shell, SWT.SINGLE | SWT.BORDER | SWT.READ_ONLY);
    Text editable = new Text(shell, SWT.SINGLE | SWT.BORDER);
    Text plain = new Text(shell, SWT.SINGLE | SWT.BORDER);
    Text multi = new Text(shell, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER);
    for (Text text : List.of(readOnly, editable, plain, multi)) {
      if (text != plain) {
        text.setMessage("MMMM mock hint");
      }
      text.setSize(200, 30);
    }
    // Shown twice without focus: an open shell focuses its first field, which draws natively.
    shell.notifyListeners(SWT.Show, new Event());
    shell.notifyListeners(SWT.Show, new Event());
    RGB field = Win32DialogColors.FIELD.getRGB();
    assertTrue(Contrast.ratio(Win32DialogColors.HINT.getRGB(), field) >= 4.5);
    assertTrue(Contrast.ratio(Win32DialogColors.TEXT.getRGB(), field) >= 4.5);
    for (Text text : List.of(readOnly, editable)) {
      assertEquals(1, text.getListeners(SWT.Paint).length, "one hint listener");
      for (int type : new int[] {SWT.FocusIn, SWT.FocusOut, SWT.Modify}) {
        assertEquals(1, text.getListeners(type).length, "redraw on " + type);
      }
      assertEquals(field, at(paint(text, null), 1, 1), "" + text);
      // ClearType tints every glyph pixel at 150 %, so the ink is read from the paint GC.
      assertEquals(Win32DialogColors.HINT.getRGB(), ink(text), "" + text);
      text.setText("mock");
      assertEquals(OWN, at(paint(text, null), 1, 1), "holding text");
    }
    ImageData filled = paint(plain, null);
    assertEquals(field, at(filled, 1, 1), "no hint");
    assertEquals(OWN, ink(plain), "no hint drawn");
    Rectangle left = new Rectangle(0, 0, 10, plain.getClientArea().height);
    ImageData part = paint(plain, left);
    assertEquals(field, at(part, 1, 1), "inside the damaged area");
    assertEquals(OWN, at(part, 20, 1), "outside the damaged area");
    assertEquals(0, multi.getListeners(SWT.Paint).length);
  }

  /**
   * Sends a Paint event over the given bounds, or the whole image, of an image filled with {@link
   * #OWN}.
   */
  private ImageData paint(Text text, Rectangle bounds) {
    Rectangle area = text.getClientArea();
    Image image = new Image(display, Math.max(1, area.width), Math.max(1, area.height));
    GC gc = new GC(image);
    try {
      gc.setBackground(new Color(OWN));
      gc.fillRectangle(0, 0, area.width, area.height);
      Event event = new Event();
      event.gc = gc;
      event.setBounds(bounds != null ? bounds : new Rectangle(0, 0, area.width, area.height));
      text.notifyListeners(SWT.Paint, event);
    } finally {
      gc.dispose();
    }
    try {
      return image.getImageData();
    } finally {
      image.dispose();
    }
  }

  /**
   * Sends a Paint event as {@link #paint} and returns the GC's foreground after it, {@link #OWN} at
   * the start.
   */
  private RGB ink(Text text) {
    Rectangle area = text.getClientArea();
    Image image = new Image(display, Math.max(1, area.width), Math.max(1, area.height));
    GC gc = new GC(image);
    try {
      gc.setForeground(new Color(OWN));
      Event event = new Event();
      event.gc = gc;
      event.setBounds(new Rectangle(0, 0, area.width, area.height));
      text.notifyListeners(SWT.Paint, event);
      return gc.getForeground().getRGB();
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  private static RGB at(ImageData data, int x, int y) {
    return data.palette.getRGB(data.getPixel(x, y));
  }

  @Test
  void win32LeavesAShellItDidNotColor() {
    Win32DialogColors colors = Win32DialogColors.INSTANCE;
    Label label = new Label(owner, SWT.NONE);
    RGB before = label.getForeground().getRGB();
    owner.open();
    assertFalse(colors.colored(owner));
    assertEquals(before, label.getForeground().getRGB());
  }

  @Test
  void win32AfterALightStartSetsNothing() {
    display.setData(DialogColors.NATIVE_DARK, null);
    Win32DialogColors colors = Win32DialogColors.INSTANCE;
    Shell shell = new Shell(owner, SWT.DIALOG_TRIM);
    RGB background = shell.getBackground().getRGB();
    colors.apply(shell);
    shell.open();
    assertFalse(colors.colored(shell));
    assertEquals(background, shell.getBackground().getRGB());
    assertEquals(owner.getBackground().getRGB(), colors.background(owner).getRGB());
    assertEquals(
        display.getSystemColor(SWT.COLOR_WIDGET_DISABLED_FOREGROUND).getRGB(),
        colors.disabledText(display).getRGB());
  }

  @Test
  void win32ColorsADialogCreatedAfterASwitchToDark() {
    display.setData(DialogColors.NATIVE_DARK, null);
    Win32DialogColors colors = Win32DialogColors.INSTANCE;
    colors.apply(new Shell(owner, SWT.DIALOG_TRIM));
    display.setData(DialogColors.NATIVE_DARK, Boolean.TRUE);
    Shell shell = new Shell(owner, SWT.DIALOG_TRIM);
    colors.apply(shell);
    assertTrue(colors.colored(shell));
    final Text later = new Text(shell, SWT.BORDER);
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return later.getBackground().getRGB().equals(Win32DialogColors.FIELD.getRGB());
              }
            },
            BOUND));
    assertEquals(Win32DialogColors.BODY.getRGB(), colors.background(owner).getRGB());
  }

  @Test
  void nativeSetsNothing() {
    NativeDialogColors colors = NativeDialogColors.INSTANCE;
    Shell shell = new Shell(owner, SWT.DIALOG_TRIM);
    RGB background = shell.getBackground().getRGB();
    colors.apply(shell);
    Label label = new Label(shell, SWT.NONE);
    RGB foreground = label.getForeground().getRGB();
    shell.open();
    assertFalse(colors.colored(shell));
    assertEquals(background, shell.getBackground().getRGB());
    assertEquals(foreground, label.getForeground().getRGB());
    assertEquals(owner.getBackground().getRGB(), colors.background(owner).getRGB());
  }
}
