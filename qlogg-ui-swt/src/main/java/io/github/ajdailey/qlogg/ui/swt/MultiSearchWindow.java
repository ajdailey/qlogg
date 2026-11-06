package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.controller.ApplicationController;
import io.github.ajdailey.qlogg.app.controller.DocumentController;
import io.github.ajdailey.qlogg.app.controller.MultiSearchController;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.core.search.SearchLimit;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

/**
 * Edit > Search > Search All Open Files: a modeless window with one row per file group of the
 * {@link MultiSearchController}, its match count and state, under a summary line. Choosing a row
 * shows that file's tab with its search's results in the filtered view, where a row goes to its
 * line. Stop cancels every search of the group.
 */
final class MultiSearchWindow {
  private final Shell shell;
  private final Label summary;
  private final Table groups;
  private final MultiSearchController search;
  private final MultiSearchController.Listener listener =
      new MultiSearchController.Listener() {
        @Override
        public void resultsChanged() {
          sync();
        }
      };

  MultiSearchWindow(Shell parent, final ApplicationController application) {
    this.search = application.multiSearch();
    shell = Dialogs.shell(parent, UiText.MULTI_TITLE.text(), false);
    summary = Dialogs.label(shell, "");
    groups = new Table(shell, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
    GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
    data.widthHint = 420;
    data.heightHint = 200;
    groups.setLayoutData(data);
    groups.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.item instanceof TableItem item
                && item.getData() instanceof DocumentController document
                && !document.isClosed()) {
              application.activate(document);
              document.search().show(-1);
            }
          }
        });
    Composite buttons = Dialogs.buttonRow(shell, 1);
    Dialogs.button(
        buttons,
        UiText.TOOLBAR_STOP.text(),
        new Runnable() {
          @Override
          public void run() {
            search.cancel();
          }
        });
    shell.setDefaultButton(
        Dialogs.button(buttons, UiText.BUTTON_CLOSE.text(), Dialogs.closer(shell)));
    search.addListener(listener);
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            search.removeListener(listener);
          }
        });
    sync();
  }

  /** Rewrites the summary and one row per group. */
  void sync() {
    if (shell.isDisposed()) {
      return;
    }
    List<MultiSearchController.FileResults> results = search.results();
    summary.setText(
        UiText.MULTI_SUMMARY.format(search.text(), search.totalMatches(), results.size()));
    while (groups.getItemCount() > results.size()) {
      groups.getItem(groups.getItemCount() - 1).dispose();
    }
    while (groups.getItemCount() < results.size()) {
      new TableItem(groups, SWT.NONE);
    }
    for (int i = 0; i < results.size(); i++) {
      MultiSearchController.FileResults group = results.get(i);
      TableItem item = groups.getItem(i);
      item.setData(group.document());
      item.setText(text(group));
    }
  }

  /** One group's row; a limit or skipped lines show as the search bar words them. */
  static String text(MultiSearchController.FileResults group) {
    String title = group.document().state().title();
    SearchViewState state = group.state();
    return switch (state.phase()) {
      case RUNNING -> UiText.MULTI_RUNNING.format(title, state.matchCount());
      case FAILED -> UiText.MULTI_FAILED.format(title, String.valueOf(state.error()));
      case CANCELED -> UiText.MULTI_STOPPED.format(title, state.matchCount());
      default ->
          state.limit() == SearchLimit.NONE && state.skippedLines() == 0
              ? UiText.MULTI_DONE.format(title, state.matchCount())
              : UiText.MULTI_FAILED.format(title, state.statusText());
    };
  }

  Table groups() {
    return groups;
  }

  Label summary() {
    return summary;
  }

  boolean isDisposed() {
    return shell.isDisposed();
  }

  void open() {
    sync();
    if (shell.isVisible()) {
      shell.setActive();
      return;
    }
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
  }

  Shell shell() {
    return shell;
  }
}
