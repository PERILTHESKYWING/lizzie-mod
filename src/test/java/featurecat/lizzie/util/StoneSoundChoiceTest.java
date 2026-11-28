package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import org.junit.jupiter.api.Test;

class StoneSoundChoiceTest {
  @Test
  void capturesPickTheCaptureSounds() {
    assertEquals("Stone.wav", sound(0, 0, 0, 0));
    assertEquals("deadStone.wav", sound(0, 0, 2, 0));
    assertEquals("deadStoneMore.wav", sound(1, 0, 4, 0));
    assertEquals("deadStone.wav", sound(0, 5, 0, 6));
    assertEquals("deadStoneMore.wav", sound(0, 0, 0, 3));
    assertEquals("Stone.wav", Utils.stoneSound(new BoardHistoryNode(BoardData.empty(9, 9))));
  }

  private static String sound(int blackBefore, int whiteBefore, int blackAfter, int whiteAfter) {
    BoardData before = BoardData.empty(9, 9);
    before.blackCaptures = blackBefore;
    before.whiteCaptures = whiteBefore;
    BoardData after = BoardData.empty(9, 9);
    after.blackCaptures = blackAfter;
    after.whiteCaptures = whiteAfter;
    BoardHistoryNode node = new BoardHistoryNode(before);
    return Utils.stoneSound(node.add(new BoardHistoryNode(after)));
  }
}
