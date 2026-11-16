package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qlogg.app.controller.EncodingChoices;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** {@link EncodingCombo} before and after its source delivers the choices, under Xvfb. */
@Tag("swt")
class EncodingComboTest {
  private final List<EncodingChoices.Listener> waiting = new ArrayList<EncodingChoices.Listener>();
  private final MainWindow.EncodingSource source =
      new MainWindow.EncodingSource() {
        @Override
        public void load(EncodingChoices.Listener listener) {
          waiting.add(listener);
        }
      };
  private final Charset ibm437 = Charset.forName("IBM437");
  private final EncodingChoices choices =
      new EncodingChoices(
          List.of(StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1), List.of(ibm437));
  private Display display;
  private Shell shell;

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display);
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  @Test
  void listsTheCommonCharsetsThenFillsOnceAndKeepsTheSelection() {
    Combo combo = new EncodingCombo(shell, "ISO-8859-1", source).combo();
    assertEquals(1, waiting.size());
    assertArrayEquals(names(EncodingChoices.commonCharsets()), combo.getItems());
    assertEquals("ISO-8859-1", combo.getText());
    combo.select(combo.indexOf("UTF-8"));

    waiting.get(0).loaded(choices);
    assertArrayEquals(new String[] {"UTF-8", "ISO-8859-1", "IBM437"}, combo.getItems());
    assertEquals("UTF-8", combo.getText());
    waiting.get(0).loaded(new EncodingChoices(List.of(StandardCharsets.UTF_16LE), List.of()));
    assertArrayEquals(new String[] {"UTF-8", "ISO-8859-1", "IBM437"}, combo.getItems());
  }

  @Test
  void aCurrentCharsetOutsideTheCommonOnesIsListedAndSelectedUntilTheFillArrives() {
    Combo combo = new EncodingCombo(shell, "IBM437", source).combo();
    String[] items = combo.getItems();
    assertEquals("IBM437", items[items.length - 1]);
    assertEquals("IBM437", combo.getText());
    waiting.get(0).loaded(choices);
    assertEquals("IBM437", combo.getText());
    assertEquals(3, combo.getItemCount());
  }

  @Test
  void aDeliveryAfterTheBoxIsDisposedIsDropped() {
    new EncodingCombo(shell, "UTF-8", source).combo().dispose();
    waiting.get(0).loaded(choices);
  }

  private static String[] names(List<Charset> charsets) {
    String[] names = new String[charsets.size()];
    for (int i = 0; i < names.length; i++) {
      names[i] = charsets.get(i).name();
    }
    return names;
  }
}
