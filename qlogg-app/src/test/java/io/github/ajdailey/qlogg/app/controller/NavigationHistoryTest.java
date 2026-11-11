package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NavigationHistoryTest {
  private final NavigationHistory history = new NavigationHistory();

  @Test
  void backRecordsTheCurrentLineSoForwardReturnsToIt() {
    history.record(10);
    history.record(20);
    assertTrue(history.canBack());
    assertFalse(history.canForward());
    assertEquals(20, history.back(30));
    assertEquals(10, history.back(20));
    assertEquals(-1, history.back(10));
    assertEquals(20, history.forward());
    assertEquals(30, history.forward());
    assertEquals(-1, history.forward());
    assertArrayEquals(new long[] {10, 20, 30}, history.entries());
  }

  @Test
  void aJumpAfterGoBackDropsTheForwardEntries() {
    history.record(10);
    history.record(20);
    assertEquals(20, history.back(30));
    assertEquals(10, history.back(20));
    history.record(10);
    assertArrayEquals(new long[] {10}, history.entries());
    assertFalse(history.canForward());
    history.record(40);
    assertArrayEquals(new long[] {10, 40}, history.entries());
  }

  @Test
  void theNewestLineIsNotRecordedTwiceAndTheOldestGoesPastTheLimit() {
    history.record(5);
    history.record(5);
    assertArrayEquals(new long[] {5}, history.entries());
    for (int i = 0; i < NavigationHistory.LIMIT + 3; i++) {
      history.record(100 + i);
    }
    long[] entries = history.entries();
    assertEquals(NavigationHistory.LIMIT, entries.length);
    assertEquals(103, entries[0]);
    assertEquals(100 + NavigationHistory.LIMIT + 2, entries[entries.length - 1]);
  }

  @Test
  void clampPullsEntriesOntoTheLastLineAndMergesNeighbors() {
    history.record(5);
    history.record(50);
    history.record(80);
    history.record(90);
    assertEquals(90, history.back(95));
    assertEquals(80, history.back(90));
    assertEquals(2, history.position());
    history.clamp(30);
    assertArrayEquals(new long[] {5, 29}, history.entries());
    assertEquals(1, history.position());
    assertEquals(5, history.back(29));
    history.clamp(0);
    assertArrayEquals(new long[] {5, 29}, history.entries());
  }

  @Test
  void aDepartureIsRecordedOnlyWhenCommittedAndAMoveOfItsOwnDropsIt() {
    history.depart(7);
    history.depart(12);
    history.commit();
    assertArrayEquals(new long[] {7}, history.entries());
    history.depart(20);
    history.stay();
    history.commit();
    assertArrayEquals(new long[] {7}, history.entries());
  }

  @Test
  void takeOverCopiesTheEntriesAndPosition() {
    history.record(1);
    history.record(2);
    assertEquals(2, history.back(3));
    NavigationHistory copy = new NavigationHistory();
    copy.takeOver(history);
    assertArrayEquals(history.entries(), copy.entries());
    assertEquals(history.position(), copy.position());
    assertEquals(3, copy.forward());
  }
}
