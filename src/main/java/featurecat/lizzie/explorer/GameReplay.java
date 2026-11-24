package featurecat.lizzie.explorer;

import java.util.List;

/**
 * Replays a stored game record to the first main-line position matching a canonical key, using the
 * same position rules as {@link SgfGameIndexer} (the player who moves next is to move; simple ko
 * binds only the opponent of the capturer).
 */
public final class GameReplay {
  /**
   * The board at the matching position.
   *
   * @param movesPlayed moves played before the position (go to this move number to show it)
   * @param nextMove the point played next in the game (row-major), {@link GoPosition#PASS}, or
   *     {@link CanonicalPosition#END}
   */
  public record Snapshot(
      int width, int height, byte[] cells, int movesPlayed, int nextMove, boolean blackToMove) {}

  private GameReplay() {}

  /** The first position of the record's main line whose canonical key is {@code key}, or null. */
  public static Snapshot find(String record, PositionKey key) {
    List<SgfReader.Node> roots;
    try {
      roots = SgfReader.parse(record);
    } catch (RuntimeException e) {
      return null;
    }
    if (roots.isEmpty()) return null;
    SgfReader.Node root = roots.get(0);
    int[] size;
    try {
      size = SgfGameIndexer.parseSize(root.first("SZ"));
    } catch (RuntimeException e) {
      return null;
    }
    GoPosition position = new GoPosition(size[0], size[1]);
    byte lastMover = GoPosition.EMPTY;
    int moves = 0;
    for (SgfReader.Node node = root; node != null; ) {
      SgfGameIndexer.applySetupForReplay(node, position);
      byte color = node.has("B") ? GoPosition.BLACK : node.has("W") ? GoPosition.WHITE : 0;
      if (color != 0) {
        int move;
        try {
          move =
              SgfGameIndexer.parseMove(
                  node.first(color == GoPosition.BLACK ? "B" : "W"),
                  position.width(),
                  position.height());
        } catch (RuntimeException e) {
          return null;
        }
        Snapshot s = match(position, lastMover, color, move, moves, key);
        if (s != null) return s;
        position.play(move, color);
        lastMover = color;
        moves++;
      }
      node = node.children().isEmpty() ? null : node.children().get(0);
    }
    return match(position, lastMover, GoPosition.EMPTY, CanonicalPosition.END, moves, key);
  }

  private static Snapshot match(
      GoPosition position,
      byte lastMover,
      byte nextColor,
      int nextMove,
      int moves,
      PositionKey key) {
    boolean blackToMove =
        nextColor != GoPosition.EMPTY ? nextColor == GoPosition.BLACK : position.isBlackToMove();
    int ko = position.koPoint();
    if (ko >= 0
        && (lastMover == GoPosition.EMPTY || (lastMover == GoPosition.BLACK) == blackToMove)) {
      ko = -1;
    }
    CanonicalPosition c =
        CanonicalPosition.of(
            position.width(), position.height(), position.cells(), blackToMove, ko);
    if (!c.key().equals(key)) return null;
    return new Snapshot(
        position.width(), position.height(), position.copyCells(), moves, nextMove, blackToMove);
  }
}
