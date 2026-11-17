package featurecat.lizzie.explorer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Symmetry-normalised identity of a Go position.
 *
 * <p>For every symmetry valid for the board size, the transformed position (stones, side to move,
 * ko point, board size) is hashed with fixed, deterministic 128-bit Zobrist tables. The smallest
 * hash is the canonical key. The tables never change between runs, so keys can be persisted.
 *
 * <p>When the position is itself symmetric, several transforms reach the canonical key (the
 * stabiliser). Next moves are normalised as the minimum point index over all of those transforms,
 * so moves that are equivalent on a symmetric board are always aggregated into one entry.
 */
public final class CanonicalPosition {
  /** Largest board side supported (SGF allows up to 52). */
  public static final int MAX_SIZE = 52;

  /** Move code for "the game ended here" (no next move). */
  public static final int END = -2;

  private static final int STRIDE = 64;
  private static final long[] BLACK_HI = new long[STRIDE * STRIDE];
  private static final long[] BLACK_LO = new long[STRIDE * STRIDE];
  private static final long[] WHITE_HI = new long[STRIDE * STRIDE];
  private static final long[] WHITE_LO = new long[STRIDE * STRIDE];
  private static final long[] KO_HI = new long[STRIDE * STRIDE];
  private static final long[] KO_LO = new long[STRIDE * STRIDE];
  private static final long[] SIZE_HI = new long[STRIDE * STRIDE];
  private static final long[] SIZE_LO = new long[STRIDE * STRIDE];
  private static final long WHITE_TO_MOVE_HI;
  private static final long WHITE_TO_MOVE_LO;

  static {
    // Fixed seed: persisted keys depend on these values. Never change the seed or the fill order.
    SplittableRandom random = new SplittableRandom(0x4C697A7A69654F45L);
    fill(random, BLACK_HI, BLACK_LO);
    fill(random, WHITE_HI, WHITE_LO);
    fill(random, KO_HI, KO_LO);
    fill(random, SIZE_HI, SIZE_LO);
    WHITE_TO_MOVE_HI = random.nextLong();
    WHITE_TO_MOVE_LO = random.nextLong();
  }

  private static void fill(SplittableRandom random, long[] hi, long[] lo) {
    for (int i = 0; i < hi.length; i++) {
      hi[i] = random.nextLong();
      lo[i] = random.nextLong();
    }
  }

  private final int width;
  private final int height;
  private final PositionKey key;
  private final List<BoardSymmetry> stabilizer;

  private CanonicalPosition(
      int width, int height, PositionKey key, List<BoardSymmetry> stabilizer) {
    this.width = width;
    this.height = height;
    this.key = key;
    this.stabilizer = stabilizer;
  }

  public static CanonicalPosition of(GoPosition position) {
    return of(
        position.width(),
        position.height(),
        position.cells(),
        position.isBlackToMove(),
        position.koPoint());
  }

  /**
   * @param cells row-major cells ({@link GoPosition#EMPTY}/{@link GoPosition#BLACK}/{@link
   *     GoPosition#WHITE})
   * @param koPoint row-major point the side to move may not retake, or -1
   */
  public static CanonicalPosition of(
      int width, int height, byte[] cells, boolean blackToMove, int koPoint) {
    List<BoardSymmetry> symmetries = BoardSymmetry.validFor(width, height);
    int count = symmetries.size();
    long[] hi = new long[count];
    long[] lo = new long[count];
    long baseHi = SIZE_HI[width * STRIDE + height];
    long baseLo = SIZE_LO[width * STRIDE + height];
    if (!blackToMove) {
      baseHi ^= WHITE_TO_MOVE_HI;
      baseLo ^= WHITE_TO_MOVE_LO;
    }
    for (int s = 0; s < count; s++) {
      hi[s] = baseHi;
      lo[s] = baseLo;
    }
    for (int i = 0; i < cells.length; i++) {
      byte c = cells[i];
      if (c == GoPosition.EMPTY) continue;
      long[] tHi = c == GoPosition.BLACK ? BLACK_HI : WHITE_HI;
      long[] tLo = c == GoPosition.BLACK ? BLACK_LO : WHITE_LO;
      int x = i % width;
      int y = i / width;
      for (int s = 0; s < count; s++) {
        BoardSymmetry sym = symmetries.get(s);
        int slot = sym.mapX(x, y, width, height) + sym.mapY(x, y, width, height) * STRIDE;
        hi[s] ^= tHi[slot];
        lo[s] ^= tLo[slot];
      }
    }
    if (koPoint >= 0) {
      int x = koPoint % width;
      int y = koPoint / width;
      for (int s = 0; s < count; s++) {
        BoardSymmetry sym = symmetries.get(s);
        int slot = sym.mapX(x, y, width, height) + sym.mapY(x, y, width, height) * STRIDE;
        hi[s] ^= KO_HI[slot];
        lo[s] ^= KO_LO[slot];
      }
    }
    int best = 0;
    for (int s = 1; s < count; s++) {
      if (PositionKey.compare(hi[s], lo[s], hi[best], lo[best]) < 0) best = s;
    }
    List<BoardSymmetry> ties = new ArrayList<>(2);
    for (int s = 0; s < count; s++) {
      if (hi[s] == hi[best] && lo[s] == lo[best]) ties.add(symmetries.get(s));
    }
    return new CanonicalPosition(width, height, new PositionKey(hi[best], lo[best]), ties);
  }

  public PositionKey key() {
    return key;
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  /** Transforms that map this position onto the canonical one (size > 1 when symmetric). */
  public List<BoardSymmetry> stabilizer() {
    return stabilizer;
  }

  /**
   * Normalises a move made in this position. Pass and {@link #END} are returned unchanged.
   *
   * @param move row-major point, {@link GoPosition#PASS} or {@link #END}
   */
  public int canonicalMove(int move) {
    if (move < 0) return move;
    int best = Integer.MAX_VALUE;
    for (BoardSymmetry s : stabilizer) {
      best = Math.min(best, s.mapIndex(move, width, height));
    }
    return best;
  }

  /** Maps a canonical move back onto this board's orientation (one representative point). */
  public int toDisplay(int canonicalMove) {
    if (canonicalMove < 0) return canonicalMove;
    return stabilizer.get(0).inverse().mapIndex(canonicalMove, width, height);
  }

  /** All points on this board equivalent to the canonical move (more than one when symmetric). */
  public Set<Integer> displayEquivalents(int canonicalMove) {
    Set<Integer> result = new LinkedHashSet<>();
    if (canonicalMove < 0) return result;
    for (BoardSymmetry s : stabilizer) {
      result.add(s.inverse().mapIndex(canonicalMove, width, height));
    }
    return result;
  }
}
