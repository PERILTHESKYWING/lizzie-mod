package featurecat.lizzie.explorer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The eight geometric symmetries of a square board (the dihedral group D4). On a rectangular board
 * only the four transforms that keep the width and height ({@link #IDENTITY}, {@link #FLIP_X},
 * {@link #FLIP_Y} and {@link #ROT_180}) are valid.
 *
 * <p>Colours are never swapped: black/white inversion is a different equivalence and is not part of
 * this group.
 */
public enum BoardSymmetry {
  IDENTITY(false),
  ROT_90(true),
  ROT_180(false),
  ROT_270(true),
  FLIP_X(false),
  FLIP_Y(false),
  TRANSPOSE(true),
  ANTI_TRANSPOSE(true);

  private final boolean swapsAxes;

  BoardSymmetry(boolean swapsAxes) {
    this.swapsAxes = swapsAxes;
  }

  /** True when this transform maps a {@code width x height} board onto itself. */
  public boolean isValidFor(int width, int height) {
    return width == height || !swapsAxes;
  }

  /** The transforms valid for a board of this size, identity first. */
  public static List<BoardSymmetry> validFor(int width, int height) {
    List<BoardSymmetry> result = new ArrayList<>(8);
    for (BoardSymmetry s : values()) {
      if (s.isValidFor(width, height)) result.add(s);
    }
    return Collections.unmodifiableList(result);
  }

  /** Transformed x of point (x,y). Only meaningful when {@link #isValidFor} holds. */
  public int mapX(int x, int y, int width, int height) {
    switch (this) {
      case IDENTITY:
      case FLIP_Y:
        return x;
      case ROT_180:
      case FLIP_X:
        return width - 1 - x;
      case ROT_90: // (x,y) -> (n-1-y, x)
        return width - 1 - y;
      case ROT_270: // (x,y) -> (y, n-1-x)
      case TRANSPOSE:
        return y;
      case ANTI_TRANSPOSE:
        return width - 1 - y;
      default:
        throw new AssertionError(this);
    }
  }

  /** Transformed y of point (x,y). Only meaningful when {@link #isValidFor} holds. */
  public int mapY(int x, int y, int width, int height) {
    switch (this) {
      case IDENTITY:
      case FLIP_X:
        return y;
      case ROT_180:
      case FLIP_Y:
        return height - 1 - y;
      case ROT_90:
      case TRANSPOSE:
        return x;
      case ROT_270:
      case ANTI_TRANSPOSE:
        return height - 1 - x;
      default:
        throw new AssertionError(this);
    }
  }

  /** Maps a row-major point index ({@code x + y * width}); negative indexes pass through. */
  public int mapIndex(int index, int width, int height) {
    if (index < 0) return index;
    int x = index % width;
    int y = index / width;
    return mapX(x, y, width, height) + mapY(x, y, width, height) * width;
  }

  /** The transform that undoes this one. */
  public BoardSymmetry inverse() {
    switch (this) {
      case ROT_90:
        return ROT_270;
      case ROT_270:
        return ROT_90;
      default:
        return this; // the identity, ROT_180 and all reflections are involutions
    }
  }
}
