package io.github.ajdailey.qlogg.app.highlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.ajdailey.qlogg.core.model.Rgb;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LabelStateTest {
  private final List<ColorLabel> labels = ColorLabel.defaults();

  @Test
  void numberedLabelsMoveAndToggle() {
    LabelState state = LabelState.EMPTY.apply(2, "flagword");
    assertEquals(2, state.labelOf("flagword"));
    state = state.apply(4, "flagword");
    assertEquals(4, state.labelOf("flagword"));
    assertEquals(1, state.size());
    state = state.apply(4, "flagword");
    assertEquals(-1, state.labelOf("flagword"));
    assertEquals(0, state.size());
  }

  @Test
  void nextCyclesThroughCycleLabelsAndRemovesALabeledText() {
    List<ColorLabel> cycle = new ArrayList<ColorLabel>(labels);
    cycle.set(1, new ColorLabel("off", null, Rgb.of(0x808080), false));
    LabelState state = LabelState.EMPTY.next("a", cycle);
    assertEquals(0, state.labelOf("a"));
    state = state.next("b", cycle);
    assertEquals(2, state.labelOf("b"));
    state = state.next("a", cycle);
    assertEquals(-1, state.labelOf("a"));
    assertEquals(3, state.current());
    for (int i = 0; i < 6; i++) {
      state = state.next("t" + i, cycle);
    }
    assertEquals(0, state.labelOf("t5"));
  }

  @Test
  void clearResetsTheCycle() {
    LabelState state = LabelState.EMPTY.next("a", labels).next("b", labels);
    assertEquals(1, state.current());
    LabelState cleared = LabelState.EMPTY;
    assertEquals(0, cleared.size());
    assertEquals(0, cleared.next("c", labels).labelOf("c"));
  }

  @Test
  void noCycleLabelsAndTheTextCap() {
    List<ColorLabel> none = new ArrayList<ColorLabel>();
    for (ColorLabel label : labels) {
      none.add(new ColorLabel(label.name(), label.foreground(), label.background(), false));
    }
    assertSame(LabelState.EMPTY, LabelState.EMPTY.next("x", none));
    LabelState state = LabelState.EMPTY;
    for (int i = 0; i < LabelState.MAX_TEXTS; i++) {
      state = state.apply(i % 9, "w" + i);
    }
    assertSame(state, state.apply(0, "one more"));
    assertEquals(LabelState.MAX_TEXTS, state.rules(labels).size());
  }

  @Test
  void costFollowsTheFormula() {
    LabelState state = LabelState.EMPTY.apply(0, "abcd").apply(1, "xy");
    assertEquals(2L * LabelState.BYTES_PER_TEXT + 2 * 6, state.retainedBytes());
    assertEquals(Rgb.of(0xb3c6ff), state.rules(labels).get(0).background());
  }
}
