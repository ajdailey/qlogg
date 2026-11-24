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
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
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
 * dark dialog colors, which keep their own, and controls added after the dialog opens. The drawn
 * result on win32 is {@code Win32DarkDialogsTest}.
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
    Win32DialogColors colors = new Win32DialogColors(display);
    Shell shell = new Shell(owner, SWT.DIALOG_TRIM);
    colors.prepare(shell);
    assertTrue(DialogColors.colored(shell));
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
    RGB comboBackground = readOnly.getBackground().getRGB();
    colors.apply(shell);
    assertEquals(Win32DialogColors.BODY.getRGB(), shell.getBackground().getRGB());
    assertEquals(Win32DialogColors.BODY.getRGB(), page.getBackground().getRGB());
    assertEquals(Win32DialogColors.TEXT.getRGB(), label.getForeground().getRGB());
    assertEquals(Win32DialogColors.TEXT.getRGB(), link.getForeground().getRGB());
    assertEquals(Win32DialogColors.LINK.getRGB(), link.getLinkForeground().getRGB());
    assertEquals(Win32DialogColors.TEXT.getRGB(), check.getForeground().getRGB());
    assertEquals(Win32DialogColors.FIELD.getRGB(), text.getBackground().getRGB());
    assertEquals(Win32DialogColors.TEXT.getRGB(), text.getForeground().getRGB());
    assertEquals(Win32DialogColors.FIELD.getRGB(), table.getBackground().getRGB());
    assertEquals(Win32DialogColors.FIELD.getRGB(), table.getHeaderBackground().getRGB());
    assertEquals(Win32DialogColors.TEXT.getRGB(), table.getHeaderForeground().getRGB());
    // The dark start draws push buttons and read-only combos dark already.
    assertEquals(pushBackground, push.getBackground().getRGB());
    assertEquals(pushForeground, push.getForeground().getRGB());
    assertEquals(comboBackground, readOnly.getBackground().getRGB());
    assertEquals(OWN, own.getForeground().getRGB());
    assertEquals(Win32DialogColors.BODY.getRGB(), colors.background(owner).getRGB());
  }

  @Test
  void win32ColorsAControlAddedAfterTheDialogOpens() {
    Win32DialogColors colors = new Win32DialogColors(display);
    Shell shell = new Shell(owner, SWT.DIALOG_TRIM);
    colors.prepare(shell);
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
    Win32DialogColors colors = new Win32DialogColors(display);
    Shell shell = new Shell(owner, SWT.DIALOG_TRIM);
    colors.prepare(shell);
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
    colors.apply(shell);
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

  @Test
  void win32LeavesAnUnpreparedShell() {
    Win32DialogColors colors = new Win32DialogColors(display);
    Label label = new Label(owner, SWT.NONE);
    RGB before = label.getForeground().getRGB();
    colors.apply(owner);
    assertFalse(DialogColors.colored(owner));
    assertEquals(before, label.getForeground().getRGB());
  }

  @Test
  void win32AfterALightStartSetsNothing() {
    display.setData(DialogColors.NATIVE_DARK, null);
    Win32DialogColors colors = new Win32DialogColors(display);
    Shell shell = new Shell(owner, SWT.DIALOG_TRIM);
    RGB background = shell.getBackground().getRGB();
    colors.prepare(shell);
    colors.apply(shell);
    assertFalse(DialogColors.colored(shell));
    assertEquals(background, shell.getBackground().getRGB());
    assertEquals(owner.getBackground().getRGB(), colors.background(owner).getRGB());
    assertEquals(
        display.getSystemColor(SWT.COLOR_WIDGET_DISABLED_FOREGROUND).getRGB(),
        colors.disabledText(display).getRGB());
  }

  @Test
  void win32BuiltBeforeADarkStartColorsAfterIt() {
    display.setData(DialogColors.NATIVE_DARK, null);
    Win32DialogColors colors = new Win32DialogColors(display);
    display.setData(DialogColors.NATIVE_DARK, Boolean.TRUE);
    Shell shell = new Shell(owner, SWT.DIALOG_TRIM);
    colors.prepare(shell);
    assertTrue(DialogColors.colored(shell));
    assertEquals(Win32DialogColors.BODY.getRGB(), colors.background(owner).getRGB());
  }

  @Test
  void nativeSetsNothing() {
    NativeDialogColors colors = new NativeDialogColors();
    Shell shell = new Shell(owner, SWT.DIALOG_TRIM);
    RGB background = shell.getBackground().getRGB();
    colors.prepare(shell);
    Label label = new Label(shell, SWT.NONE);
    RGB foreground = label.getForeground().getRGB();
    colors.apply(shell);
    assertFalse(DialogColors.colored(shell));
    assertEquals(background, shell.getBackground().getRGB());
    assertEquals(foreground, label.getForeground().getRGB());
    assertEquals(owner.getBackground().getRGB(), colors.background(owner).getRGB());
  }
}
