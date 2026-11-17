package featurecat.lizzie.explorer;

import java.util.List;

/**
 * Next-move statistics for one canonical position.
 *
 * <p>Main-line mode (default): {@code games} is the number of distinct games whose main line
 * reaches the position; each contributes exactly one vote, the move played at its first occurrence.
 * A move's percentage is {@code move.games / games * 100}. Games that ended in the position count
 * in {@code games} and in {@link #endedGames}, so the move percentages plus the ended share sum to
 * 100%.
 *
 * <p>Raw counts include every occurrence, including repeats of the position within one game.
 *
 * <p>Variation mode: {@code games} is the number of distinct games reaching the position in any
 * line; a move counts each game once however many lines play it, so percentages may sum to more
 * than 100%.
 */
public record OpeningStats(
    PositionKey key,
    boolean includesVariations,
    int games,
    int endedGames,
    int rawOccurrences,
    List<MoveStat> moves) {

  /** One next move, in canonical orientation (point index, or {@link GoPosition#PASS}). */
  public record MoveStat(int canonicalMove, int games, int rawOccurrences) {
    public boolean isPass() {
      return canonicalMove == GoPosition.PASS;
    }
  }

  public static OpeningStats empty(PositionKey key, boolean includesVariations) {
    return new OpeningStats(key, includesVariations, 0, 0, 0, List.of());
  }

  /** Percentage of matching games that chose {@code move}; 0 when no game matches. */
  public double percent(MoveStat move) {
    return games == 0 ? 0 : move.games() * 100.0 / games;
  }
}
