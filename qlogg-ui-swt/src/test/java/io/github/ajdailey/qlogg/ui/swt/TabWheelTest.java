package io.github.ajdailey.qlogg.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** {@link TabWheel} with no display. */
class TabWheelTest {
  private static final int TABS = 5;

  @Test
  void aNotchStepsOneTab() {
    TabWheel wheel = new TabWheel();
    assertEquals(3, wheel.add(-3, 2, TABS));
    assertEquals(1, wheel.add(3, 2, TABS));
    assertEquals(2, wheel.add(0, 2, TABS));
  }

  @Test
  void smallCountsAddUpToAStep() {
    TabWheel wheel = new TabWheel();
    assertEquals(2, wheel.add(-1, 2, TABS));
    assertEquals(2, wheel.add(-1, 2, TABS));
    assertEquals(3, wheel.add(-2, 2, TABS));
    assertEquals(3, wheel.add(-1, 3, TABS));
    assertEquals(4, wheel.add(-1, 3, TABS));
  }

  @Test
  void aLargeCountStepsSeveralTabs() {
    TabWheel wheel = new TabWheel();
    assertEquals(2, wheel.add(-7, 0, TABS));
    assertEquals(3, wheel.add(-2, 2, TABS));
  }

  @Test
  void aReversalRestartsTheSum() {
    TabWheel wheel = new TabWheel();
    assertEquals(2, wheel.add(-2, 2, TABS));
    assertEquals(2, wheel.add(2, 2, TABS));
    assertEquals(1, wheel.add(1, 2, TABS));
  }

  @Test
  void theEndsStopTheSelectionAndClearTheSum() {
    TabWheel wheel = new TabWheel();
    assertEquals(4, wheel.add(-3, 4, TABS));
    assertEquals(4, wheel.add(-2, 4, TABS));
    assertEquals(4, wheel.add(-2, 4, TABS));
    assertEquals(0, wheel.add(3, 0, TABS));
    assertEquals(0, wheel.add(2, 0, TABS));
    assertEquals(0, wheel.add(-2, 0, TABS));
    assertEquals(1, wheel.add(-1, 0, TABS));
  }
}
