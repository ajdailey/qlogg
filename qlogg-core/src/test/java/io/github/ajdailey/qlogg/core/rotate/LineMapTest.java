package io.github.ajdailey.qlogg.core.rotate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class LineMapTest {
  private static final Path OLDEST = Path.of("app.log.2");
  private static final Path MIDDLE = Path.of("app.log.1");
  private static final Path ACTIVE = Path.of("app.log");

  /** Old members of 3, 0 and 4 lines; the first dropped, the others two lines further down. */
  private static LineMap map() {
    return new LineMap(
        List.of(
            new LineMap.Entry(OLDEST, 0, 3, LineMap.DROPPED),
            new LineMap.Entry(MIDDLE, 3, 0, 2),
            new LineMap.Entry(ACTIVE, 3, 4, 2)));
  }

  @Test
  void linesMoveWithTheirMember() {
    LineMap map = map();
    assertEquals(LineMap.DROPPED, map.map(0));
    assertEquals(LineMap.DROPPED, map.map(2));
    assertEquals(2, map.map(3));
    assertEquals(5, map.map(6));
  }

  @Test
  void linesPastTheOldMembersAreDropped() {
    assertEquals(LineMap.DROPPED, map().map(7));
    assertEquals(LineMap.DROPPED, map().map(-1));
  }

  @Test
  void droppedListsTheMembersThatLeft() {
    assertEquals(List.of(OLDEST), map().dropped());
  }
}
