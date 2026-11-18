package featurecat.lizzie.explorer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Replays one SGF game on a {@link GoPosition} and records, for every position reached, its
 * canonical key and the canonical next move.
 *
 * <p>Main line: every position before a move, plus the final position (next move {@link
 * CanonicalPosition#END}). The first time a position occurs in the main line it is flagged {@link
 * #FLAG_FIRST}; later repeats are kept for raw occurrence counts. Variations, when enabled, are
 * flagged {@link #FLAG_VARIATION} and never affect main-line statistics.
 */
public final class SgfGameIndexer {
  public static final int FLAG_FIRST = 1;
  public static final int FLAG_VARIATION = 2;

  /** Positions recorded for one game, as parallel arrays. */
  public static final class IndexedGame {
    public final GameMetadata metadata;
    public final String fingerprint;
    public final int mainLineMoves;
    public final long[] keyHi;
    public final long[] keyLo;
    public final short[] moves;
    public final byte[] flags;

    /**
     * Digest of the first {@link #PREFIX_MOVES} moves as a sequence of canonical positions (so a
     * mirrored copy has the same digest), or "" for shorter games.
     */
    public final String prefixDigest;

    IndexedGame(
        GameMetadata metadata,
        String fingerprint,
        int mainLineMoves,
        long[] keyHi,
        long[] keyLo,
        short[] moves,
        byte[] flags,
        String prefixDigest) {
      this.prefixDigest = prefixDigest;
      this.metadata = metadata;
      this.fingerprint = fingerprint;
      this.mainLineMoves = mainLineMoves;
      this.keyHi = keyHi;
      this.keyLo = keyLo;
      this.moves = moves;
      this.flags = flags;
    }

    public int size() {
      return moves.length;
    }
  }

  private static final int MAX_MOVES_PER_LINE = 2000;
  private static final int MAX_VARIATION_DEPTH = 200;

  private final boolean includeVariations;
  private int count;
  private long[] hi = new long[256];
  private long[] lo = new long[256];
  private short[] mv = new short[256];
  private byte[] fl = new byte[256];
  private final Set<PositionKey> seenMainLine = new HashSet<>();

  /** Games whose first this many moves match are the same game (see OpeningDatabase). */
  public static final int PREFIX_MOVES = 100;

  private final MessageDigest prefix = sha256();
  private int prefixPositions;

  private SgfGameIndexer(boolean includeVariations) {
    this.includeVariations = includeVariations;
  }

  /**
   * @param source a label for where the game came from (file name, ZIP entry)
   * @throws IllegalArgumentException for games that cannot be indexed (bad size, bad coordinates)
   */
  public static IndexedGame index(SgfReader.Node root, String source, boolean includeVariations) {
    return new SgfGameIndexer(includeVariations).run(root, source);
  }

  private IndexedGame run(SgfReader.Node root, String source) {
    int[] size = parseSize(root.first("SZ"));
    int width = size[0];
    int height = size[1];
    GoPosition position = new GoPosition(width, height);
    MessageDigest digest = sha256();
    digest.update(("SZ" + width + ":" + height + "|").getBytes(StandardCharsets.UTF_8));
    for (String id : new String[] {"PB", "PW", "DT", "RE"}) {
      String v = root.first(id);
      digest.update(
          (id + "=" + (v == null ? "" : v.trim()) + "|").getBytes(StandardCharsets.UTF_8));
    }

    // Walk the main line iteratively; variations hang off it.
    SgfReader.Node node = root;
    byte lastMover = GoPosition.EMPTY;
    int moves = 0;
    while (node != null && moves < MAX_MOVES_PER_LINE) {
      applySetup(node, position, digest);
      byte color = moveColor(node);
      if (color != GoPosition.EMPTY) {
        int move = parseMove(node.first(color == GoPosition.BLACK ? "B" : "W"), width, height);
        digest.update(
            ((color == GoPosition.BLACK ? "B" : "W") + move + ";")
                .getBytes(StandardCharsets.UTF_8));
        record(position, lastMover, color, move, false);
        position.play(move, color);
        lastMover = color;
        moves++;
      }
      List<SgfReader.Node> children = node.children();
      if (includeVariations) {
        for (int i = 1; i < children.size(); i++) {
          walkVariation(children.get(i), position.copy(), lastMover, 0);
        }
      }
      node = children.isEmpty() ? null : children.get(0);
    }
    record(position, lastMover, GoPosition.EMPTY, CanonicalPosition.END, false);

    GameMetadata metadata =
        new GameMetadata(
            root.first("PB"),
            root.first("PW"),
            root.first("BR"),
            root.first("WR"),
            root.first("DT"),
            root.first("EV"),
            root.first("RO"),
            root.first("PC"),
            root.first("RE"),
            width,
            height,
            root.first("RU"),
            parseDouble(root.first("KM")),
            parseInt(root.first("HA")),
            source);
    return new IndexedGame(
        metadata,
        HexFormat.of().formatHex(digest.digest()),
        moves,
        Arrays.copyOf(hi, count),
        Arrays.copyOf(lo, count),
        Arrays.copyOf(mv, count),
        Arrays.copyOf(fl, count),
        moves >= PREFIX_MOVES ? HexFormat.of().formatHex(prefix.digest()) : "");
  }

  /**
   * Records a position and the move played from it.
   *
   * @param nextColor colour of the move, or EMPTY for the end of a line
   * @param nextMove row-major point, PASS or END
   */
  private void record(
      GoPosition position, byte lastMover, byte nextColor, int nextMove, boolean variation) {
    // The player who actually moves next defines "to move"; at the end of a line, the board's turn.
    boolean blackToMove =
        nextColor != GoPosition.EMPTY ? nextColor == GoPosition.BLACK : position.isBlackToMove();
    // The ko restriction only binds the opponent of the player who just captured.
    int ko = position.koPoint();
    if (ko >= 0
        && (lastMover == GoPosition.EMPTY || (lastMover == GoPosition.BLACK) == blackToMove)) {
      ko = -1;
    }
    CanonicalPosition canonical =
        CanonicalPosition.of(
            position.width(), position.height(), position.cells(), blackToMove, ko);
    int flags = 0;
    if (variation) {
      flags |= FLAG_VARIATION;
    } else {
      if (seenMainLine.add(canonical.key())) flags |= FLAG_FIRST;
      // The positions before moves 1..100 and after move 100 pin down the first 100 moves.
      if (prefixPositions++ <= PREFIX_MOVES) {
        for (long v : new long[] {canonical.key().hi(), canonical.key().lo()}) {
          for (int b = 0; b < 8; b++) prefix.update((byte) (v >>> (8 * b)));
        }
      }
    }
    add(canonical.key(), canonical.canonicalMove(nextMove), flags);
  }

  private void walkVariation(SgfReader.Node start, GoPosition position, byte lastMover, int depth) {
    if (depth > MAX_VARIATION_DEPTH) return;
    SgfReader.Node node = start;
    int moves = 0;
    while (node != null && moves < MAX_MOVES_PER_LINE) {
      applySetup(node, position, null);
      byte color = moveColor(node);
      if (color != GoPosition.EMPTY) {
        int move =
            parseMove(
                node.first(color == GoPosition.BLACK ? "B" : "W"),
                position.width(),
                position.height());
        record(position, lastMover, color, move, true);
        position.play(move, color);
        lastMover = color;
        moves++;
      }
      List<SgfReader.Node> children = node.children();
      for (int i = 1; i < children.size(); i++) {
        walkVariation(children.get(i), position.copy(), lastMover, depth + 1);
      }
      node = children.isEmpty() ? null : children.get(0);
    }
  }

  private void add(PositionKey key, int move, int flags) {
    if (count == mv.length) {
      int n = count * 2;
      hi = Arrays.copyOf(hi, n);
      lo = Arrays.copyOf(lo, n);
      mv = Arrays.copyOf(mv, n);
      fl = Arrays.copyOf(fl, n);
    }
    hi[count] = key.hi();
    lo[count] = key.lo();
    mv[count] = (short) move;
    fl[count] = (byte) flags;
    count++;
  }

  private static byte moveColor(SgfReader.Node node) {
    if (node.has("B")) return GoPosition.BLACK;
    if (node.has("W")) return GoPosition.WHITE;
    return GoPosition.EMPTY;
  }

  private static void applySetup(SgfReader.Node node, GoPosition position, MessageDigest digest) {
    boolean any = false;
    for (String id : new String[] {"AB", "AW", "AE"}) {
      byte color =
          id.equals("AB")
              ? GoPosition.BLACK
              : id.equals("AW") ? GoPosition.WHITE : GoPosition.EMPTY;
      for (String value : node.all(id)) {
        for (int p : expandPoints(value, position.width(), position.height())) {
          position.setStone(p, color);
          any = true;
          if (digest != null) digest.update((id + p + ";").getBytes(StandardCharsets.UTF_8));
        }
      }
    }
    String pl = node.first("PL");
    if (pl != null && !pl.isBlank()) {
      char c = Character.toUpperCase(pl.trim().charAt(0));
      if (c == 'B' || c == 'W') position.setBlackToMove(c == 'B');
    } else if (any && node.has("AB") && !node.has("AW") && position.isBlackToMove()) {
      // Handicap stones: White moves first unless PL says otherwise.
      position.setBlackToMove(false);
    }
  }

  /** Expands a point or a compressed rectangle ("aa:cc"). */
  static List<Integer> expandPoints(String value, int width, int height) {
    List<Integer> points = new ArrayList<>();
    String v = value.trim();
    if (v.isEmpty()) return points;
    int colon = v.indexOf(':');
    if (colon < 0) {
      int p = parseMove(v, width, height);
      if (p >= 0) points.add(p);
      return points;
    }
    int a = parseMove(v.substring(0, colon), width, height);
    int b = parseMove(v.substring(colon + 1), width, height);
    if (a < 0 || b < 0) throw new IllegalArgumentException("Bad point range " + value);
    int x0 = Math.min(a % width, b % width);
    int x1 = Math.max(a % width, b % width);
    int y0 = Math.min(a / width, b / width);
    int y1 = Math.max(a / width, b / width);
    for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) points.add(x + y * width);
    return points;
  }

  /**
   * SGF point to row-major index; "" (and "tt" on boards up to 19x19) is a pass.
   *
   * @throws IllegalArgumentException for coordinates outside the board
   */
  static int parseMove(String value, int width, int height) {
    if (value == null) return GoPosition.PASS;
    String v = value.trim();
    if (v.isEmpty()) return GoPosition.PASS;
    if (v.length() != 2) throw new IllegalArgumentException("Bad SGF point '" + v + "'");
    int x = sgfCoord(v.charAt(0));
    int y = sgfCoord(v.charAt(1));
    if (x == 19 && y == 19 && width <= 19 && height <= 19) return GoPosition.PASS;
    if (x < 0 || y < 0 || x >= width || y >= height) {
      throw new IllegalArgumentException("SGF point '" + v + "' outside the board");
    }
    return x + y * width;
  }

  private static int sgfCoord(char c) {
    if (c >= 'a' && c <= 'z') return c - 'a';
    if (c >= 'A' && c <= 'Z') return c - 'A' + 26;
    return -1;
  }

  static int[] parseSize(String sz) {
    if (sz == null || sz.isBlank()) return new int[] {19, 19};
    try {
      String[] parts = sz.trim().split(":");
      int w = Integer.parseInt(parts[0].trim());
      int h = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : w;
      if (w < 2 || h < 2 || w > CanonicalPosition.MAX_SIZE || h > CanonicalPosition.MAX_SIZE) {
        throw new IllegalArgumentException("Unsupported board size " + sz);
      }
      return new int[] {w, h};
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Bad board size " + sz, e);
    }
  }

  private static double parseDouble(String s) {
    if (s == null || s.isBlank()) return Double.NaN;
    try {
      return Double.parseDouble(s.trim());
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }

  private static int parseInt(String s) {
    if (s == null || s.isBlank()) return 0;
    try {
      return Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
