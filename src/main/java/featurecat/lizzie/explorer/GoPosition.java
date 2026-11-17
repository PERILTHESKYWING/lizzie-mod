package featurecat.lizzie.explorer;

import java.util.Arrays;

/**
 * A small, self-contained Go board used for indexing. It is independent of the application's global
 * {@code Board} size and Zobrist tables so it can run on background threads.
 *
 * <p>Points are row-major: {@code index = x + y * width}. Cells hold {@link #EMPTY}, {@link #BLACK}
 * or {@link #WHITE}.
 */
public final class GoPosition {
  public static final byte EMPTY = 0;
  public static final byte BLACK = 1;
  public static final byte WHITE = 2;

  /** Move code for a pass. */
  public static final int PASS = -1;

  private final int width;
  private final int height;
  private final byte[] cells;
  private boolean blackToMove = true;
  private int koPoint = -1;

  public GoPosition(int width, int height) {
    if (width < 1
        || height < 1
        || width > CanonicalPosition.MAX_SIZE
        || height > CanonicalPosition.MAX_SIZE) {
      throw new IllegalArgumentException("Unsupported board size " + width + "x" + height);
    }
    this.width = width;
    this.height = height;
    this.cells = new byte[width * height];
  }

  /** Independent copy, used to branch into SGF variations. */
  public GoPosition copy() {
    GoPosition p = new GoPosition(width, height);
    System.arraycopy(cells, 0, p.cells, 0, cells.length);
    p.blackToMove = blackToMove;
    p.koPoint = koPoint;
    return p;
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  public boolean isBlackToMove() {
    return blackToMove;
  }

  public void setBlackToMove(boolean blackToMove) {
    this.blackToMove = blackToMove;
  }

  /** The point the side to move may not immediately retake, or -1. */
  public int koPoint() {
    return koPoint;
  }

  public byte get(int index) {
    return cells[index];
  }

  /** Read-only view for hashing; callers must not modify it. */
  byte[] cells() {
    return cells;
  }

  public byte[] copyCells() {
    return Arrays.copyOf(cells, cells.length);
  }

  /** Setup edit (SGF AB/AW/AE). Clears any ko restriction; no captures are resolved. */
  public void setStone(int index, byte color) {
    cells[index] = color;
    koPoint = -1;
  }

  /**
   * Plays a move for {@code color} (or a pass when {@code index == PASS}), resolving captures and
   * suicide, and hands the turn to the other colour. Moves onto an occupied point are recorded
   * as-is (the stone is overwritten) so that malformed records never abort indexing.
   *
   * @return false when the point was occupied (the record is suspicious)
   */
  public boolean play(int index, byte color) {
    byte opponent = color == BLACK ? WHITE : BLACK;
    koPoint = -1;
    blackToMove = color != BLACK;
    if (index == PASS) return true;
    boolean wasEmpty = cells[index] == EMPTY;
    cells[index] = color;
    int capturedCount = 0;
    int lastCaptured = -1;
    int[] neighbours = neighbours(index);
    for (int n : neighbours) {
      if (n >= 0 && cells[n] == opponent && !hasLiberty(n)) {
        int removed = removeChain(n);
        capturedCount += removed;
        lastCaptured = n;
      }
    }
    if (capturedCount == 0 && !hasLiberty(index)) {
      removeChain(index); // suicide (allowed by some rule sets)
      return wasEmpty;
    }
    if (capturedCount == 1 && isSingleStoneInAtari(index)) {
      koPoint = lastCaptured;
    }
    return wasEmpty;
  }

  /**
   * Simple-ko point implied by a finished move, computed from the board after the move. This is the
   * shared definition used both for indexing and for querying the displayed board.
   *
   * @param after cells after the move, row-major
   * @param move the point just played
   * @param capturedPoints points captured by that move
   * @return the point the opponent may not retake immediately, or -1
   */
  public static int koAfterMove(
      int width, int height, byte[] after, int move, int[] capturedPoints) {
    if (move < 0 || capturedPoints.length != 1) return -1;
    GoPosition p = new GoPosition(width, height);
    System.arraycopy(after, 0, p.cells, 0, after.length);
    return p.isSingleStoneInAtari(move) ? capturedPoints[0] : -1;
  }

  private boolean isSingleStoneInAtari(int index) {
    byte color = cells[index];
    int liberties = 0;
    for (int n : neighbours(index)) {
      if (n < 0) continue;
      if (cells[n] == color) return false;
      if (cells[n] == EMPTY) liberties++;
    }
    return liberties == 1;
  }

  private int[] neighbours(int index) {
    int x = index % width;
    int y = index / width;
    return new int[] {
      x > 0 ? index - 1 : -1,
      x < width - 1 ? index + 1 : -1,
      y > 0 ? index - width : -1,
      y < height - 1 ? index + width : -1
    };
  }

  private boolean hasLiberty(int start) {
    byte color = cells[start];
    boolean[] seen = new boolean[cells.length];
    int[] stack = new int[cells.length];
    int top = 0;
    stack[top++] = start;
    seen[start] = true;
    while (top > 0) {
      int p = stack[--top];
      for (int n : neighbours(p)) {
        if (n < 0 || seen[n]) continue;
        if (cells[n] == EMPTY) return true;
        if (cells[n] == color) {
          seen[n] = true;
          stack[top++] = n;
        }
      }
    }
    return false;
  }

  private int removeChain(int start) {
    byte color = cells[start];
    int[] stack = new int[cells.length];
    int top = 0;
    int removed = 0;
    stack[top++] = start;
    cells[start] = EMPTY;
    while (top > 0) {
      int p = stack[--top];
      removed++;
      for (int n : neighbours(p)) {
        if (n >= 0 && cells[n] == color) {
          cells[n] = EMPTY;
          stack[top++] = n;
        }
      }
    }
    return removed;
  }
}
