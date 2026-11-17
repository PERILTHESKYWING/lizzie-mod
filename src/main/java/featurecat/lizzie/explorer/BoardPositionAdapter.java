package featurecat.lizzie.explorer;

import featurecat.lizzie.rules.Stone;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Converts the application's board arrays (column-major, {@code index = x * height + y}) into the
 * explorer's row-major position and derives the simple-ko point the same way indexing does.
 */
public final class BoardPositionAdapter {
  private BoardPositionAdapter() {}

  /**
   * @param stones current stones (application layout)
   * @param parentStones stones before the last move, or null when unknown
   * @param lastMove the last move {x, y}, empty for a pass or no move
   * @param blackToPlay side to move
   */
  public static CanonicalPosition canonical(
      int width,
      int height,
      Stone[] stones,
      Stone[] parentStones,
      Optional<int[]> lastMove,
      boolean blackToPlay) {
    byte[] cells = toCells(width, height, stones);
    int ko = -1;
    if (parentStones != null && lastMove != null && lastMove.isPresent()) {
      int[] m = lastMove.get();
      if (m[0] >= 0 && m[1] >= 0 && m[0] < width && m[1] < height) {
        int moveIndex = m[0] + m[1] * width;
        byte mover = cells[moveIndex];
        // Ko binds only the opponent of the player who just moved.
        if (mover != GoPosition.EMPTY && (mover == GoPosition.BLACK) != blackToPlay) {
          byte[] before = toCells(width, height, parentStones);
          byte opponent = mover == GoPosition.BLACK ? GoPosition.WHITE : GoPosition.BLACK;
          List<Integer> captured = new ArrayList<>(2);
          for (int i = 0; i < cells.length && captured.size() < 2; i++) {
            if (before[i] == opponent && cells[i] == GoPosition.EMPTY) captured.add(i);
          }
          if (captured.size() == 1) {
            ko =
                GoPosition.koAfterMove(
                    width, height, cells, moveIndex, new int[] {captured.get(0)});
          }
        }
      }
    }
    return CanonicalPosition.of(width, height, cells, blackToPlay, ko);
  }

  static byte[] toCells(int width, int height, Stone[] stones) {
    byte[] cells = new byte[width * height];
    if (stones == null || stones.length < width * height) return cells;
    for (int x = 0; x < width; x++) {
      for (int y = 0; y < height; y++) {
        Stone s = stones[x * height + y];
        cells[x + y * width] =
            s == Stone.BLACK || s == Stone.BLACK_RECURSED
                ? GoPosition.BLACK
                : s == Stone.WHITE || s == Stone.WHITE_RECURSED
                    ? GoPosition.WHITE
                    : GoPosition.EMPTY;
      }
    }
    return cells;
  }
}
