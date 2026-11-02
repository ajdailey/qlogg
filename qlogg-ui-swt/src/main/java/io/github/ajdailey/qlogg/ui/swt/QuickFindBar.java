package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.QuickFindController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.QuickFindListener;
import io.github.ajdailey.qlogg.app.view.QuickFindState;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Text;

/**
 * The quick find row above the status bar, shown while {@link QuickFindController} is open: the
 * pattern field, Ignore case, Incremental and Regex boxes, Previous, Next, the last notice and
 * Close. Enter finds in the direction the bar was opened for, Shift+Enter finds backward, and
 * Escape closes it. Incremental and Regex are the {@code quickFind.*} preferences.
 */
final class QuickFindBar {
  private final Composite bar;
  private final Text field;
  private final Button ignoreCase;
  private final Button incremental;
  private final Button regex;
  private final Label notice;
  private final ApplicationController application;
  private final QuickFindController quickFind;
  private boolean syncing;
  private final QuickFindListener listener =
      new QuickFindListener() {
        @Override
        public void stateChanged(QuickFindState state) {
          show(state);
        }
      };

  QuickFindBar(Composite parent, ApplicationController application) {
    this.application = application;
    this.quickFind = application.quickFind();
    bar = new Composite(parent, SWT.NONE);
    GridLayout layout = new GridLayout(9, false);
    layout.marginHeight = 2;
    bar.setLayout(layout);
    Label label = new Label(bar, SWT.NONE);
    label.setText(UiText.QUICK_FIND_LABEL.text());
    field = new Text(bar, SWT.SINGLE | SWT.BORDER);
    GridData fieldData = new GridData(SWT.FILL, SWT.CENTER, false, false);
    fieldData.widthHint = 240;
    field.setLayoutData(fieldData);
    ignoreCase = check(UiText.QUICK_FIND_IGNORE_CASE);
    incremental = check(UiText.QUICK_FIND_INCREMENTAL);
    regex = check(UiText.QUICK_FIND_REGEX);
    button(
        UiText.BUTTON_PREVIOUS,
        new Runnable() {
          @Override
          public void run() {
            quickFind.previous();
          }
        });
    button(
        UiText.BUTTON_NEXT,
        new Runnable() {
          @Override
          public void run() {
            quickFind.next();
          }
        });
    notice = new Label(bar, SWT.NONE);
    notice.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    button(
        UiText.BUTTON_CLOSE,
        new Runnable() {
          @Override
          public void run() {
            quickFind.close();
          }
        });
    listen();
    quickFind.addListener(listener);
    bar.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            quickFind.removeListener(listener);
          }
        });
    show(quickFind.state());
  }

  private Button check(UiText label) {
    Button button = new Button(bar, SWT.CHECK);
    button.setText(label.text());
    return button;
  }

  private void button(UiText label, final Runnable action) {
    Button button = new Button(bar, SWT.PUSH);
    button.setText(label.text());
    button.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            action.run();
          }
        });
  }

  private void listen() {
    field.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!syncing) {
              quickFind.setText(field.getText());
            }
          }
        });
    field.addListener(
        SWT.DefaultSelection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            quickFind.submit();
          }
        });
    field.addListener(
        SWT.KeyDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.keyCode == SWT.ESC) {
              quickFind.close();
              event.doit = false;
            } else if ((event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR)
                && (event.stateMask & SWT.MOD2) != 0) {
              quickFind.previous();
              event.doit = false;
            }
          }
        });
    ignoreCase.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            quickFind.setIgnoreCase(ignoreCase.getSelection());
          }
        });
    Listener options =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            application.setQuickFindOptions(incremental.getSelection(), regex.getSelection());
          }
        };
    incremental.addListener(SWT.Selection, options);
    regex.addListener(SWT.Selection, options);
  }

  Composite control() {
    return bar;
  }

  Text field() {
    return field;
  }

  String noticeText() {
    return notice.getText();
  }

  /** Focuses the field and selects its text. */
  void focus() {
    if (!field.isDisposed()) {
      field.setFocus();
      field.selectAll();
    }
  }

  private void show(QuickFindState state) {
    if (bar.isDisposed()) {
      return;
    }
    syncing = true;
    try {
      if (!field.getText().equals(state.text())) {
        field.setText(state.text());
      }
    } finally {
      syncing = false;
    }
    ignoreCase.setSelection(state.ignoreCase());
    incremental.setSelection(state.incremental());
    regex.setSelection(state.regex());
    notice.setText(state.running() ? UiText.QUICK_FIND_RUNNING.text() : state.notice());
    GridData data = (GridData) bar.getLayoutData();
    if (data != null && data.exclude == state.open()) {
      data.exclude = !state.open();
      bar.setVisible(state.open());
      bar.getParent().layout(true, true);
    }
    bar.layout();
  }
}
