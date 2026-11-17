package featurecat.lizzie.explorer;

/**
 * A 128-bit canonical position identity. It covers board size, stone placement, side to move and
 * the simple-ko point, all after symmetry normalisation.
 */
public record PositionKey(long hi, long lo) implements Comparable<PositionKey> {
  @Override
  public int compareTo(PositionKey o) {
    int c = Long.compareUnsigned(hi, o.hi);
    return c != 0 ? c : Long.compareUnsigned(lo, o.lo);
  }

  public static int compare(long hiA, long loA, long hiB, long loB) {
    int c = Long.compareUnsigned(hiA, hiB);
    return c != 0 ? c : Long.compareUnsigned(loA, loB);
  }

  @Override
  public String toString() {
    return String.format("%016x%016x", hi, lo);
  }
}
