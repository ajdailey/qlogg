package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.ClipboardService;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * A modeless window of read-only monospace text with Copy and Close: usage text, licenses, the log.
 */
final class TextWindow {
  private final Shell shell;
  private final Text text;
  private final Button close;
  private String content;

  TextWindow(Shell parent, String title, String initial, final ClipboardService clipboard) {
    shell = Dialogs.shell(parent, title, false);
    content = initial;
    text = new Text(shell, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
    final Font font =
        new Font(shell.getDisplay(), Dialogs.monospaceName(), Dialogs.monospaceSize(), SWT.NORMAL);
    text.setFont(font);
    text.setText(content);
    text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    Composite buttons = Dialogs.buttonRow(shell, 1);
    Dialogs.button(
        buttons,
        UiText.BUTTON_COPY.text(),
        new Runnable() {
          @Override
          public void run() {
            clipboard.setText(content);
          }
        });
    close = Dialogs.button(buttons, UiText.BUTTON_CLOSE.text(), Dialogs.closer(shell));
    shell.setDefaultButton(close);
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            font.dispose();
          }
        });
  }

  void open() {
    Dialogs.open(shell, 720, 520);
  }

  Shell shell() {
    return shell;
  }

  /**
   * Replaces the text, shows its first line and focuses Close.
   *
   * @param value the new text
   */
  void setContent(String value) {
    content = value;
    text.setText(value);
    text.setSelection(0);
    text.setTopIndex(0);
    close.setFocus();
  }

  /** Shows the first line and focuses Close. */
  void showStart() {
    text.setSelection(0);
    text.setTopIndex(0);
    close.setFocus();
  }
}
