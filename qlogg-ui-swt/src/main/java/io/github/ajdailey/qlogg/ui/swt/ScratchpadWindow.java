package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.scratch.Transforms;
import java.time.ZoneId;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Tools > Scratchpad: a modeless window of editable text tabs ({@code Ctrl+N} or New Tab adds one)
 * with the built-in {@link Transforms}, which replace the selection, or the whole tab when nothing
 * is selected, and a line of values derived from the selection. Text sent from a log view appends
 * to, or replaces, the current tab. Transforms and values take at most {@link #MAX_CHARS} chars, so
 * one runs in milliseconds on the UI thread; a longer input shows a notice. Nothing in it runs code
 * from the text.
 */
final class ScratchpadWindow {
  /** Most chars a transform or the derived values take, and most chars sent to a tab at once. */
  static final int MAX_CHARS = 1 << 20;

  private final Shell shell;
  private final CTabFolder tabs;
  private final Label values;
  private final Font font;
  private int created;

  ScratchpadWindow(Shell parent) {
    shell = Dialogs.shell(parent, UiText.SCRATCHPAD_TITLE.text(), false);
    font =
        new Font(shell.getDisplay(), Dialogs.monospaceName(), Dialogs.monospaceSize(), SWT.NORMAL);
    Composite row = new Composite(shell, SWT.NONE);
    RowLayout layout = new RowLayout(SWT.HORIZONTAL);
    layout.marginLeft = 0;
    layout.spacing = 8;
    row.setLayout(layout);
    Dialogs.button(
        row,
        UiText.SCRATCHPAD_NEW_TAB.text(),
        new Runnable() {
          @Override
          public void run() {
            newTab();
          }
        });
    final Button transform = new Button(row, SWT.PUSH);
    transform.setText(UiText.SCRATCHPAD_TRANSFORMS.text());
    final Menu menu = new Menu(transform);
    for (final Transforms.Kind kind : Transforms.Kind.values()) {
      MenuItem item = new MenuItem(menu, SWT.PUSH);
      item.setText(kind.label());
      item.setData(kind);
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              apply(kind);
            }
          });
    }
    transform.setMenu(menu);
    transform.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            Rectangle bounds = transform.getBounds();
            Point at = transform.getParent().toDisplay(bounds.x, bounds.y + bounds.height);
            menu.setLocation(at);
            menu.setVisible(true);
          }
        });
    tabs = new CTabFolder(shell, SWT.TOP | SWT.BORDER | SWT.CLOSE);
    tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    values = new Label(shell, SWT.NONE);
    values.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            font.dispose();
          }
        });
    newTab();
  }

  /** Adds a tab and shows it. */
  Text newTab() {
    CTabItem item = new CTabItem(tabs, SWT.CLOSE);
    item.setText(UiText.SCRATCHPAD_TAB.format(++created));
    final Text text = new Text(tabs, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
    text.setFont(font);
    item.setControl(text);
    Listener selectionChanged =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.type == SWT.KeyDown
                && (event.stateMask & SWT.MOD1) != 0
                && (event.keyCode == 'n' || event.keyCode == 'N')) {
              event.doit = false;
              newTab().setFocus();
              return;
            }
            showValues(text);
          }
        };
    text.addListener(SWT.KeyDown, selectionChanged);
    text.addListener(SWT.KeyUp, selectionChanged);
    text.addListener(SWT.MouseUp, selectionChanged);
    tabs.setSelection(item);
    return text;
  }

  /**
   * Appends text to the current tab on a new line, or replaces the tab's text.
   *
   * @param added the text, cut at {@link #MAX_CHARS}
   * @param replace true to replace
   */
  void add(String added, boolean replace) {
    Text text = current();
    String cut = added.length() > MAX_CHARS ? added.substring(0, MAX_CHARS) : added;
    if (replace || text.getCharCount() == 0) {
      text.setText(cut);
    } else {
      text.append(text.getText().endsWith("\n") ? cut : "\n" + cut);
    }
  }

  /** Applies a transform to the selection, or to the whole tab without one. */
  void apply(Transforms.Kind kind) {
    Text text = current();
    Point range = text.getSelection();
    boolean whole = range.x == range.y;
    String input = whole ? text.getText() : text.getSelectionText();
    if (input.length() > MAX_CHARS) {
      values.setText(UiText.SCRATCHPAD_TOO_LONG.format(MAX_CHARS));
      return;
    }
    try {
      String output = Transforms.apply(kind, input);
      if (whole) {
        text.setText(output);
      } else {
        text.insert(output);
        text.setSelection(range.x, range.x + output.length());
      }
      values.setText("");
    } catch (IllegalArgumentException e) {
      values.setText(UiText.SCRATCHPAD_FAILED.format(String.valueOf(e.getMessage())));
    }
  }

  private void showValues(Text text) {
    String selected = text.getSelectionText();
    if (selected.isEmpty() || selected.length() > MAX_CHARS) {
      values.setText("");
      return;
    }
    values.setText(String.join("    ", Transforms.values(selected, ZoneId.systemDefault())));
  }

  /** The shown tab's text, a new tab when every tab was closed. */
  Text current() {
    CTabItem item = tabs.getSelection();
    if (item == null || item.getControl() == null || item.getControl().isDisposed()) {
      return newTab();
    }
    return (Text) item.getControl();
  }

  /**
   * Returns the line of derived values.
   *
   * @return the label
   */
  Label values() {
    return values;
  }

  CTabFolder tabs() {
    return tabs;
  }

  boolean isDisposed() {
    return shell.isDisposed();
  }

  void open() {
    if (shell.isVisible()) {
      shell.setActive();
      return;
    }
    Dialogs.open(shell, 640, 420);
  }

  Shell shell() {
    return shell;
  }
}
