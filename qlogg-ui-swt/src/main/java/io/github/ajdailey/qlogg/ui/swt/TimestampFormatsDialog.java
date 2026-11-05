package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.MainView;
import io.github.ajdailey.qlogg.app.view.TimeText;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import io.github.ajdailey.qlogg.core.time.TimestampParser;
import io.github.ajdailey.qlogg.core.time.UserFormat;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * View > Timestamps > Edit Formats: the user timestamp formats as a list with a name, a locating
 * regex and a formatter pattern each. Every edit compiles the selected format and parses the shown
 * file's caret line with it, so the preview shows the value the format reads before OK saves. OK
 * stays disabled while a format does not compile or two share a name. The parse runs on the UI
 * thread over at most {@link io.github.ajdailey.qlogg.core.time.TimeReader#PREFIX_CHARS} chars,
 * under the user format's regex bound.
 */
final class TimestampFormatsDialog {
  /** A format as edited, before it compiles. */
  private static final class Draft {
    String name;
    String locate;
    String pattern;

    Draft(String name, String locate, String pattern) {
      this.name = name;
      this.locate = locate;
      this.pattern = pattern;
    }
  }

  /** The locating regex and pattern of a new format. */
  static final String NEW_LOCATE = "^(?<ts>\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})";

  static final String NEW_PATTERN = "yyyy-MM-dd HH:mm:ss";

  private final Shell shell;
  private final org.eclipse.swt.widgets.List list;
  private final Text name;
  private final Text locate;
  private final Text pattern;
  private final Label preview;
  private final Label status;
  private final Button add;
  private final Button remove;
  private final Button ok;
  private final List<Draft> drafts = new ArrayList<Draft>();
  private final String caretLine;
  private final TimeContext context;
  private final ZoneId zone;
  private boolean loading;

  TimestampFormatsDialog(
      Shell parent,
      List<UserFormat> formats,
      String caretLine,
      TimeContext context,
      ZoneId zone,
      final MainView.FormatsListener listener) {
    this.caretLine = caretLine;
    this.context = context;
    this.zone = zone;
    for (UserFormat format : formats) {
      drafts.add(new Draft(format.name(), format.locate(), format.pattern()));
    }
    shell = Dialogs.shell(parent, UiText.TIME_FORMATS_TITLE.text(), true);
    ((GridLayout) shell.getLayout()).numColumns = 2;
    list = new org.eclipse.swt.widgets.List(shell, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
    GridData listData = new GridData(SWT.FILL, SWT.FILL, false, true);
    listData.widthHint = 160;
    listData.heightHint = 160;
    list.setLayoutData(listData);
    Composite fields = new Composite(shell, SWT.NONE);
    GridLayout fieldsLayout = new GridLayout(1, false);
    fieldsLayout.marginWidth = 0;
    fieldsLayout.marginHeight = 0;
    fields.setLayout(fieldsLayout);
    fields.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    name = field(fields, UiText.TIME_FORMAT_NAME);
    locate = field(fields, UiText.TIME_FORMAT_LOCATE);
    pattern = field(fields, UiText.TIME_FORMAT_PATTERN);
    preview = Dialogs.label(fields, "");
    Composite listButtons = Dialogs.buttonRow(shell, 1);
    ((GridData) listButtons.getLayoutData()).horizontalAlignment = SWT.BEGINNING;
    add =
        Dialogs.button(
            listButtons,
            UiText.BUTTON_ADD.text(),
            new Runnable() {
              @Override
              public void run() {
                addFormat();
              }
            });
    remove =
        Dialogs.button(
            listButtons,
            UiText.BUTTON_REMOVE.text(),
            new Runnable() {
              @Override
              public void run() {
                removeFormat();
              }
            });
    new Label(shell, SWT.NONE);
    status = new Label(shell, SWT.WRAP);
    status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    Composite buttons = Dialogs.buttonRow(shell, 2);
    ok =
        Dialogs.button(
            buttons,
            UiText.BUTTON_OK.text(),
            new Runnable() {
              @Override
              public void run() {
                List<UserFormat> chosen = compiled();
                if (chosen != null) {
                  shell.dispose();
                  listener.formatsChosen(chosen);
                }
              }
            });
    Dialogs.button(buttons, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
    shell.setDefaultButton(ok);
    list.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            load();
          }
        });
    Listener edited =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            edited();
          }
        };
    name.addListener(SWT.Modify, edited);
    locate.addListener(SWT.Modify, edited);
    pattern.addListener(SWT.Modify, edited);
    for (Draft draft : drafts) {
      list.add(draft.name);
    }
    if (!drafts.isEmpty()) {
      list.select(0);
    }
    load();
  }

  private static Text field(Composite parent, UiText label) {
    Dialogs.label(parent, label.text());
    Text field = new Text(parent, SWT.BORDER | SWT.SINGLE);
    field.setTextLimit(UserFormat.MAX_CHARS);
    GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
    data.widthHint = 360;
    field.setLayoutData(data);
    return field;
  }

  private void addFormat() {
    if (drafts.size() >= UserFormat.MAX_FORMATS) {
      return;
    }
    int n = drafts.size() + 1;
    String label = UiText.TIME_FORMAT_NEW_NAME.format(n);
    while (named(label)) {
      label = UiText.TIME_FORMAT_NEW_NAME.format(++n);
    }
    drafts.add(new Draft(label, NEW_LOCATE, NEW_PATTERN));
    list.add(label);
    list.select(drafts.size() - 1);
    load();
    name.setFocus();
    name.selectAll();
  }

  private boolean named(String label) {
    for (Draft draft : drafts) {
      if (draft.name.equals(label)) {
        return true;
      }
    }
    return false;
  }

  private void removeFormat() {
    int index = list.getSelectionIndex();
    if (index < 0) {
      return;
    }
    drafts.remove(index);
    list.remove(index);
    if (!drafts.isEmpty()) {
      list.select(Math.min(index, drafts.size() - 1));
    }
    load();
  }

  /** Fills the fields from the selected format. */
  private void load() {
    int index = list.getSelectionIndex();
    Draft draft = index < 0 ? null : drafts.get(index);
    loading = true;
    name.setText(draft == null ? "" : draft.name);
    locate.setText(draft == null ? "" : draft.locate);
    pattern.setText(draft == null ? "" : draft.pattern);
    loading = false;
    name.setEnabled(draft != null);
    locate.setEnabled(draft != null);
    pattern.setEnabled(draft != null);
    refresh();
  }

  private void edited() {
    int index = list.getSelectionIndex();
    if (loading || index < 0) {
      return;
    }
    Draft draft = drafts.get(index);
    draft.name = name.getText();
    draft.locate = locate.getText();
    draft.pattern = pattern.getText();
    if (!list.getItem(index).equals(draft.name)) {
      list.setItem(index, draft.name);
    }
    refresh();
  }

  /** Shows the selected format's preview and the first problem, and enables the buttons. */
  private void refresh() {
    add.setEnabled(drafts.size() < UserFormat.MAX_FORMATS);
    remove.setEnabled(list.getSelectionIndex() >= 0);
    String problem = problem();
    status.setText(problem == null ? "" : problem);
    ok.setEnabled(problem == null);
    preview.setText(previewText());
    shell.layout(true, true);
  }

  private String previewText() {
    int index = list.getSelectionIndex();
    if (index < 0 || caretLine.isEmpty()) {
      return "";
    }
    Draft draft = drafts.get(index);
    UserFormat format;
    try {
      format = new UserFormat(draft.name, draft.locate, draft.pattern);
    } catch (IllegalArgumentException e) {
      return "";
    }
    TimestampParser parser = format.parser(context);
    long nanos = parser.parse(caretLine);
    if (nanos == TimestampParser.NONE) {
      return UiText.TIME_FORMAT_PREVIEW_NONE.text();
    }
    return UiText.TIME_FORMAT_PREVIEW.format(
        TimeText.instant(nanos, parser.fractionDigits(), zone));
  }

  /** The first format that does not compile or repeats a name, as status text; null when none. */
  private String problem() {
    for (int i = 0; i < drafts.size(); i++) {
      Draft draft = drafts.get(i);
      try {
        new UserFormat(draft.name, draft.locate, draft.pattern);
      } catch (IllegalArgumentException e) {
        return UiText.TIME_FORMAT_INVALID.format(draft.name, firstLine(e.getMessage()));
      }
      for (int j = 0; j < i; j++) {
        if (drafts.get(j).name.equals(draft.name)) {
          return UiText.TIME_FORMAT_DUPLICATE.format(draft.name);
        }
      }
    }
    return null;
  }

  private static String firstLine(String message) {
    if (message == null) {
      return "";
    }
    int end = message.indexOf('\n');
    return end < 0 ? message : message.substring(0, end).strip();
  }

  /** The formats, or null while one does not compile or a name repeats. */
  private List<UserFormat> compiled() {
    if (problem() != null) {
      return null;
    }
    List<UserFormat> formats = new ArrayList<UserFormat>();
    for (Draft draft : drafts) {
      formats.add(new UserFormat(draft.name, draft.locate, draft.pattern));
    }
    return formats;
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
    list.setFocus();
  }

  Shell shell() {
    return shell;
  }

  Button ok() {
    return ok;
  }

  Button add() {
    return add;
  }

  Text pattern() {
    return pattern;
  }

  Text locate() {
    return locate;
  }

  String previewShown() {
    return preview.getText();
  }

  String statusShown() {
    return status.getText();
  }
}
