package io.github.ajdailey.qlogg.ui.swt;

import io.github.ajdailey.qlogg.app.controller.EncodingChoices;
import io.github.ajdailey.qlogg.ui.swt.platform.ComboListSelection;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;

/**
 * A read-only charset box filled from {@link EncodingChoices}: the common charsets and the current
 * value at once, then the common and other charsets when the source delivers them. The selection
 * survives the refill; a delivery after the first, or after the box is disposed, is dropped.
 */
final class EncodingCombo {
  private final Combo combo;
  private boolean loaded;

  /**
   * Builds the box and requests the full list.
   *
   * @param parent the parent
   * @param current the charset name to select
   * @param source delivers the choices on the UI thread
   */
  EncodingCombo(Composite parent, String current, MainWindow.EncodingSource source) {
    combo = new Combo(parent, SWT.READ_ONLY | SWT.DROP_DOWN);
    ComboListSelection.PLATFORM.draw(combo);
    show(EncodingChoices.commonCharsets(), List.<Charset>of(), current);
    source.load(
        new EncodingChoices.Listener() {
          @Override
          public void loaded(EncodingChoices choices) {
            fill(choices);
          }
        });
  }

  /**
   * Returns the box.
   *
   * @return the combo
   */
  Combo combo() {
    return combo;
  }

  private void fill(EncodingChoices choices) {
    if (loaded || combo.isDisposed()) {
      return;
    }
    loaded = true;
    show(choices.common(), choices.other(), combo.getText());
  }

  /** Lists the charsets, adds {@code current} at the end when absent, and selects it. */
  private void show(List<Charset> common, List<Charset> other, String current) {
    List<String> names = new ArrayList<String>();
    for (Charset charset : common) {
      names.add(charset.name());
    }
    for (Charset charset : other) {
      names.add(charset.name());
    }
    if (!current.isEmpty() && !names.contains(current)) {
      names.add(current);
    }
    combo.setItems(names.toArray(new String[0]));
    combo.select(names.indexOf(current));
  }
}
