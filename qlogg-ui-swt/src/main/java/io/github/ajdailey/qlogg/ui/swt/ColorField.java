package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.highlight.HighlightFile;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.core.model.Rgb;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.ColorDialog;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;

/**
 * A color setting in a dialog: a check box that turns the color on, its {@code #rrggbb} value and a
 * button that opens the platform color chooser. Off means no color, or, with {@code inverted}, the
 * check box is the "use the default" box and checked means no color.
 */
final class ColorField {
  private final Button use;
  private final Label value;
  private final Button choose;
  private final boolean inverted;
  private Rgb color = Rgb.of(0x808080);
  private boolean syncing;
  private boolean enabled = true;

  ColorField(Composite parent, String label, boolean inverted, final Runnable changed) {
    this.inverted = inverted;
    Composite row = new Composite(parent, SWT.NONE);
    GridLayout layout = new GridLayout(3, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    row.setLayout(layout);
    row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    use = SwtThemeService.check(row);
    use.setText(label);
    value = new Label(row, SWT.NONE);
    GridData valueData = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
    valueData.widthHint = 70;
    value.setLayoutData(valueData);
    choose = new Button(row, SWT.PUSH);
    choose.setText(UiText.BUTTON_CHOOSE_COLOR.text());
    use.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            sync();
            if (!syncing) {
              changed.run();
            }
          }
        });
    choose.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            ColorDialog dialog = new ColorDialog(choose.getShell());
            dialog.setRGB(new RGB(color.red(), color.green(), color.blue()));
            RGB picked = dialog.open();
            if (picked != null) {
              color = new Rgb(picked.red, picked.green, picked.blue);
              sync();
              changed.run();
            }
          }
        });
  }

  /**
   * Shows a color.
   *
   * @param rgb the color, or null for none
   */
  void set(Rgb rgb) {
    syncing = true;
    try {
      if (rgb != null) {
        color = rgb;
      }
      use.setSelection(inverted == (rgb == null));
      sync();
    } finally {
      syncing = false;
    }
  }

  /**
   * Returns the color.
   *
   * @return the color, or null for none
   */
  Rgb get() {
    return on() ? color : null;
  }

  void setEnabled(boolean value) {
    enabled = value;
    use.setEnabled(value);
    sync();
  }

  /** Forces the color on, for a setting that always has one. */
  void required() {
    use.setSelection(!inverted);
    use.setEnabled(false);
    sync();
  }

  Button use() {
    return use;
  }

  private boolean on() {
    return use.getSelection() != inverted;
  }

  private void sync() {
    choose.setEnabled(enabled && on());
    value.setText(on() ? HighlightFile.hex(color) : "");
  }
}
