package featurecat.lizzie.explorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.rules.Stone;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic SGF fixtures with hand-computed expected counts. */
class OpeningExplorerCorrectnessTest {
  @TempDir Path tmp;
  private int fileCounter;

  // ------------------------------------------------------------------ helpers

  private static int pt(String sgf, int width) {
    return SgfGameIndexer.parseMove(sgf, width, width);
  }

  private static String sgfPoint(int index, int width) {
    if (index < 0) return "";
    return "" + (char) ('a' + index % width) + (char) ('a' + index / width);
  }

  /** "(;SZ[n]PB[name];B[..];W[..]...)" with alternating colours starting with Black. */
  private static String game(String name, int size, String... moves) {
    StringBuilder sb = new StringBuilder("(;GM[1]FF[4]SZ[" + size + "]PB[" + name + "]PW[w]");
    boolean black = true;
    for (String m : moves) {
      sb.append(';').append(black ? "B[" : "W[").append(m).append(']');
      black = !black;
    }
    return sb.append(')').toString();
  }

  private static String[] transform(BoardSymmetry s, int size, String... moves) {
    String[] out = new String[moves.length];
    for (int i = 0; i < moves.length; i++) {
      out[i] = sgfPoint(s.mapIndex(pt(moves[i], size), size, size), size);
    }
    return out;
  }

  private Path write(String content) throws IOException {
    Path p = tmp.resolve("g" + (fileCounter++) + ".sgf");
    Files.writeString(p, content, StandardCharsets.UTF_8);
    return p;
  }

  private OpeningDatabase db() throws IOException {
    return OpeningDatabase.open(tmp.resolve("db"));
  }

  private static OpeningImporter.Report importInto(
      OpeningDatabase db, String collection, boolean variations, Path... files) throws IOException {
    int id = db.createCollection(collection);
    return OpeningImporter.importFiles(
        db, List.of(files), id, variations, (a, b, c, d) -> {}, () -> false);
  }

  /** Canonical position after replaying {@code moves} (alternating, Black first). */
  private static CanonicalPosition after(int size, String... moves) {
    GoPosition p = new GoPosition(size, size);
    byte color = GoPosition.BLACK;
    for (String m : moves) {
      p.play(pt(m, size), color);
      color = color == GoPosition.BLACK ? GoPosition.WHITE : GoPosition.BLACK;
    }
    return CanonicalPosition.of(p);
  }

  private static OpeningStats query(OpeningDatabase db, CanonicalPosition p) {
    return db.query(p.key(), OpeningFilter.ALL, false);
  }

  private static OpeningStats.MoveStat stat(OpeningStats s, CanonicalPosition p, String move) {
    int canonical = p.canonicalMove(move.isEmpty() ? GoPosition.PASS : pt(move, p.width()));
    for (OpeningStats.MoveStat m : s.moves()) if (m.canonicalMove() == canonical) return m;
    return null;
  }

  // ------------------------------------------------------------------ 1. identical positions

  @Test
  void identicalPositionsSameOrientationAreCountedPerGame() throws IOException {
    OpeningDatabase db = db();
    importInto(
        db,
        "pro",
        false,
        write(game("a", 19, "pd", "dd", "pp")),
        write(game("b", 19, "pd", "dd", "pp")),
        write(game("c", 19, "pd", "dd", "dq")));
    CanonicalPosition p = after(19, "pd", "dd");
    OpeningStats s = query(db, p);
    assertEquals(3, s.games());
    assertEquals(2, stat(s, p, "pp").games());
    assertEquals(1, stat(s, p, "dq").games());
    assertEquals(66.666, s.percent(stat(s, p, "pp")), 0.01);
    assertEquals(33.333, s.percent(stat(s, p, "dq")), 0.01);
    assertEquals(2, s.moves().size());
    assertEquals(pt("pp", 19), p.toDisplay(s.moves().get(0).canonicalMove())); // most popular first
  }

  // ------------------------------------------------------------------ 2. transpositions

  @Test
  void transpositionsReachTheSamePosition() throws IOException {
    OpeningDatabase db = db();
    importInto(
        db,
        "pro",
        false,
        write(game("a", 19, "pd", "dd", "pp", "dp", "qk")),
        write(game("b", 19, "pp", "dp", "pd", "dd", "fq")));
    CanonicalPosition p = after(19, "pd", "dd", "pp", "dp");
    assertEquals(p.key(), after(19, "pp", "dp", "pd", "dd").key());
    OpeningStats s = query(db, p);
    assertEquals(2, s.games());
    assertEquals(1, stat(s, p, "qk").games());
    assertEquals(1, stat(s, p, "fq").games());
  }

  // ------------------------------------------------------------------ 3-7. all eight symmetries

  @Test
  void allEightSymmetriesAggregateAndMapBackToTheDisplayedBoard() throws IOException {
    String[] base = {"qd", "dc", "pq", "oc", "dp", "qo"};
    OpeningDatabase db = db();
    List<Path> files = new ArrayList<>();
    for (BoardSymmetry s : BoardSymmetry.values()) {
      files.add(write(game(s.name(), 19, transform(s, 19, base))));
    }
    importInto(db, "sym", false, files.toArray(new Path[0]));
    assertEquals(8, db.gameCount());
    for (int k = 0; k <= 5; k++) {
      for (BoardSymmetry view : BoardSymmetry.values()) {
        String[] moves = transform(view, 19, base);
        CanonicalPosition p = after(19, java.util.Arrays.copyOf(moves, k));
        OpeningStats s = query(db, p);
        assertEquals(8, s.games(), "ply " + k + " view " + view);
        assertEquals(1, s.moves().size(), "equivalent next moves must aggregate, ply " + k);
        assertEquals(8, s.moves().get(0).games());
        assertEquals(100.0, s.percent(s.moves().get(0)), 1e-9);
        // The aggregated move maps back to a point equivalent to this view's real next move.
        int shown = p.toDisplay(s.moves().get(0).canonicalMove());
        assertTrue(
            p.displayEquivalents(s.moves().get(0).canonicalMove()).contains(pt(moves[k], 19)));
        if (p.stabilizer().size() == 1) assertEquals(pt(moves[k], 19), shown);
      }
    }
  }

  @Test
  void symmetricPositionsAggregateEquivalentMoves() throws IOException {
    OpeningDatabase db = db();
    // After Black plays tengen the board is fully symmetric: all four 3-3 answers and both
    // halves of each "small knight" pair are one move.
    importInto(
        db,
        "sym",
        false,
        write(game("a", 19, "jj", "cc")),
        write(game("b", 19, "jj", "qc")),
        write(game("c", 19, "jj", "cq")),
        write(game("d", 19, "jj", "qq")),
        write(game("e", 19, "jj", "dc")),
        write(game("f", 19, "jj", "cd")));
    CanonicalPosition p = after(19, "jj");
    assertEquals(8, p.stabilizer().size());
    OpeningStats s = query(db, p);
    assertEquals(6, s.games());
    assertEquals(2, s.moves().size());
    assertEquals(4, stat(s, p, "qq").games());
    assertEquals(2, stat(s, p, "dc").games());
    assertEquals(stat(s, p, "dc"), stat(s, p, "cd"));
    assertEquals(8, p.displayEquivalents(p.canonicalMove(pt("dc", 19))).size());
    // Empty board: one canonical first move for all four corners.
    CanonicalPosition empty = after(19);
    assertEquals(
        1,
        new java.util.HashSet<>(
                List.of(
                    empty.canonicalMove(pt("pd", 19)),
                    empty.canonicalMove(pt("dd", 19)),
                    empty.canonicalMove(pt("dp", 19)),
                    empty.canonicalMove(pt("pp", 19))))
            .size());
  }

  @Test
  void partiallySymmetricPositionKeepsDistinctMovesApart() {
    // Black at 4-4 on the main diagonal: only the diagonal reflection fixes the board.
    CanonicalPosition p = after(19, "dd");
    assertEquals(2, p.stabilizer().size());
    assertEquals(p.canonicalMove(pt("fc", 19)), p.canonicalMove(pt("cf", 19)));
    assertNotEquals(p.canonicalMove(pt("fc", 19)), p.canonicalMove(pt("qc", 19)));
  }

  // ------------------------------------------------------------------ 8-10. what must not merge

  @Test
  void sideToMoveIsPartOfThePosition() throws IOException {
    OpeningDatabase db = db();
    importInto(
        db,
        "pro",
        false,
        write(game("a", 19, "pd", "dd", "pp")), // Black to move after pd/dd
        write("(;SZ[19]PB[b]AB[pd]AW[dd]PL[W];W[pp])")); // same stones, White to move
    CanonicalPosition blackToMove = after(19, "pd", "dd");
    OpeningStats s = query(db, blackToMove);
    assertEquals(1, s.games());
    byte[] cells = new byte[361];
    cells[pt("pd", 19)] = GoPosition.BLACK;
    cells[pt("dd", 19)] = GoPosition.WHITE;
    CanonicalPosition whiteToMove = CanonicalPosition.of(19, 19, cells, false, -1);
    assertNotEquals(blackToMove.key(), whiteToMove.key());
    assertEquals(1, query(db, whiteToMove).games());
  }

  @Test
  void boardSizesNeverMix() throws IOException {
    OpeningDatabase db = db();
    importInto(
        db,
        "mixed",
        false,
        write(game("a", 9, "ee")),
        write(game("b", 13, "gg")),
        write(game("c", 19, "jj")));
    assertEquals(1, query(db, after(9)).games());
    assertEquals(1, query(db, after(13)).games());
    assertEquals(1, query(db, after(19)).games());
  }

  @Test
  void rectangularBoardsUseOnlyFourSymmetries() throws IOException {
    assertEquals(4, BoardSymmetry.validFor(9, 7).size());
    OpeningDatabase db = db();
    importInto(
        db,
        "rect",
        false,
        write("(;SZ[9:7]PB[a];B[bb];W[ee])"),
        write("(;SZ[9:7]PB[b];B[hb];W[de])"), // mirror image of the first game
        write("(;SZ[7:9]PB[c];B[bb];W[ee])")); // transposed board: a different size
    GoPosition g = new GoPosition(9, 7);
    CanonicalPosition empty = CanonicalPosition.of(g);
    assertEquals(4, empty.stabilizer().size());
    OpeningStats s = db.query(empty.key(), OpeningFilter.ALL, false);
    assertEquals(2, s.games());
    assertEquals(1, s.moves().size());
    // The transposed board's opening position is not the 9x7 one.
    assertEquals(
        1,
        db.query(CanonicalPosition.of(new GoPosition(7, 9)).key(), OpeningFilter.ALL, false)
            .games());
    // Display mapping stays inside a 9x7 board.
    int shown = empty.toDisplay(s.moves().get(0).canonicalMove());
    assertTrue(shown >= 0 && shown < 63);
  }

  @Test
  void colourInversionIsNotASymmetry() throws IOException {
    OpeningDatabase db = db();
    importInto(
        db,
        "pro",
        false,
        write(game("a", 19, "pd", "dd")),
        write("(;SZ[19]PB[b]AW[pd]PL[B];B[dd])"));
    CanonicalPosition blackStone = after(19, "pd"); // Black pd, White to move
    byte[] cells = new byte[361];
    cells[pt("pd", 19)] = GoPosition.WHITE;
    CanonicalPosition inverted = CanonicalPosition.of(19, 19, cells, true, -1);
    assertNotEquals(blackStone.key(), inverted.key());
    assertEquals(1, query(db, blackStone).games());
    assertEquals(1, query(db, inverted).games());
  }

  // ------------------------------------------------------------------ 11-12. passes, repeats

  @Test
  void passesAreSeparateMovesAndRepeatsVoteOnce() throws IOException {
    OpeningDatabase db = db();
    // B pd, W pass, B pass, W dd: "B pd, White to move" occurs twice in this game.
    importInto(
        db,
        "pro",
        false,
        write(game("a", 19, "pd", "", "tt", "dd")),
        write(game("b", 19, "pd", "dd")));
    CanonicalPosition p = after(19, "pd");
    OpeningStats s = query(db, p);
    assertEquals(2, s.games());
    assertEquals(3, s.rawOccurrences());
    OpeningStats.MoveStat pass = stat(s, p, "");
    assertTrue(pass.isPass());
    assertEquals(1, pass.games());
    assertEquals(1, pass.rawOccurrences());
    OpeningStats.MoveStat dd = stat(s, p, "dd");
    assertEquals(1, dd.games()); // game b only; game a voted with its first occurrence (pass)
    assertEquals(2, dd.rawOccurrences());
    assertEquals(GoPosition.PASS, p.toDisplay(GoPosition.PASS));
  }

  // ------------------------------------------------------------------ 13. duplicates

  @Test
  void duplicateImportsDoNotDistortStatistics() throws IOException {
    OpeningDatabase db = db();
    String sgf = game("a", 19, "pd", "dd", "pp");
    OpeningImporter.Report first = importInto(db, "pro", false, write(sgf), write(sgf));
    assertEquals(1, first.gamesAdded());
    assertEquals(1, first.duplicates());
    // Whitespace or comment differences in the same record are still the same game.
    OpeningImporter.Report second =
        importInto(db, "other", false, write(sgf.replace(";W[dd]", "\n;W[dd]C[nice]")));
    assertEquals(0, second.gamesAdded());
    assertEquals(1, second.duplicates());
    assertEquals(1, db.gameCount());
    assertEquals(1, query(db, after(19, "pd", "dd")).games());
    // Linked into both collections.
    for (OpeningDatabase.Collection c : db.collections()) assertEquals(1, c.games());
  }

  // ------------------------------------------------------------------ 14. variations

  @Test
  void variationsAreOptInAndNeverChangeMainLineStatistics() throws IOException {
    String sgf = "(;SZ[19]PB[a];B[pd](;W[dd];B[pp])(;W[dp];B[pp]))";
    Path file = write(sgf);
    OpeningDatabase withVars = OpeningDatabase.open(tmp.resolve("v"));
    importInto(withVars, "v", true, file);
    OpeningDatabase mainOnly = db();
    importInto(mainOnly, "m", false, file);
    CanonicalPosition afterPd = after(19, "pd");
    for (OpeningDatabase d : List.of(withVars, mainOnly)) {
      OpeningStats s = d.query(afterPd.key(), OpeningFilter.ALL, false);
      assertEquals(1, s.games());
      assertEquals(1, s.moves().size());
      assertEquals(1, stat(s, afterPd, "dd").games());
    }
    OpeningStats v = withVars.query(afterPd.key(), OpeningFilter.ALL, true);
    assertEquals(1, v.games());
    assertEquals(1, stat(v, afterPd, "dd").games());
    assertEquals(1, stat(v, afterPd, "dp").games());
    CanonicalPosition varOnly = after(19, "pd", "dp");
    assertEquals(0, withVars.query(varOnly.key(), OpeningFilter.ALL, false).games());
    assertEquals(1, withVars.query(varOnly.key(), OpeningFilter.ALL, true).games());
  }

  // ------------------------------------------------------------------ 15. denominators

  @Test
  void endedGamesAreInTheDenominatorAndSharesSumToOneHundred() throws IOException {
    OpeningDatabase db = db();
    importInto(
        db,
        "pro",
        false,
        write(game("a", 19, "pd", "dd")),
        write(game("b", 19, "pd", "dd", "pp")),
        write(game("c", 19, "pd", "dd", "dq")),
        write(game("d", 19, "pd", "dd", "dq")));
    CanonicalPosition p = after(19, "pd", "dd");
    OpeningStats s = query(db, p);
    assertEquals(4, s.games());
    assertEquals(1, s.endedGames());
    double sum = s.endedGames() * 100.0 / s.games();
    for (OpeningStats.MoveStat m : s.moves()) sum += s.percent(m);
    assertEquals(100.0, sum, 1e-9);
    assertEquals(50.0, s.percent(stat(s, p, "dq")), 1e-9);
  }

  // ------------------------------------------------------------------ 16. metadata

  @Test
  void missingMetadataDefaultsAndFiltersWork() throws IOException {
    OpeningDatabase db = db();
    importInto(
        db,
        "pro",
        false,
        write("(;B[pd];W[dd])"), // no SZ, players, date
        write(
            "(;SZ[19]PB[Shin Jinseo]BR[9p]PW[Ke Jie]WR[9p]DT[2024-03-01]EV[LG Cup]RE[B+R]KM[6.5]"
                + "RU[Japanese]HA[0];B[pd];W[dp])"));
    CanonicalPosition p = after(19, "pd");
    assertEquals(2, query(db, p).games());
    assertEquals(
        1, db.query(p.key(), new OpeningFilter(Set.of(), "shin", "", "", 0, 0), false).games());
    assertEquals(
        1, db.query(p.key(), new OpeningFilter(Set.of(), "", "9p", "", 0, 0), false).games());
    assertEquals(
        1, db.query(p.key(), new OpeningFilter(Set.of(), "", "", "lg", 0, 0), false).games());
    assertEquals(
        1, db.query(p.key(), new OpeningFilter(Set.of(), "", "", "", 2020, 2025), false).games());
    assertEquals(
        0, db.query(p.key(), new OpeningFilter(Set.of(), "", "", "", 2025, 0), false).games());
    List<GameMetadata> meta = db.gamesAt(p.key(), OpeningFilter.ALL, 10);
    GameMetadata full =
        meta.stream().filter(m -> !m.blackPlayer().isEmpty()).findFirst().orElseThrow();
    assertEquals("Ke Jie", full.whitePlayer());
    assertEquals(2024, full.year());
    assertEquals(6.5, full.komi(), 1e-9);
    GameMetadata empty =
        meta.stream().filter(m -> m.blackPlayer().isEmpty()).findFirst().orElseThrow();
    assertEquals(19, empty.width());
    assertTrue(Double.isNaN(empty.komi()));
    assertEquals(0, empty.year());
  }

  @Test
  void collectionSelectionRestrictsTheSample() throws IOException {
    OpeningDatabase db = db();
    importInto(db, "A", false, write(game("a", 19, "pd", "dd")));
    importInto(db, "B", false, write(game("b", 19, "pd", "dp")), write(game("c", 19, "pd", "dp")));
    int a = db.collections().get(0).id();
    int b = db.collections().get(1).id();
    CanonicalPosition p = after(19, "pd");
    assertEquals(3, query(db, p).games());
    assertEquals(1, db.query(p.key(), OpeningFilter.ALL.withCollections(Set.of(a)), false).games());
    assertEquals(2, db.query(p.key(), OpeningFilter.ALL.withCollections(Set.of(b)), false).games());
    db.deleteCollection(b);
    assertEquals(1, query(db, p).games());
    assertEquals(1, OpeningDatabase.open(tmp.resolve("db")).gameCount());
  }

  // ------------------------------------------------------------------ ko

  @Test
  void simpleKoIsPartOfTheKeyAndAgreesWithTheBoardAdapter() {
    // Ko shape on a 5x5 board: Black captures a White stone at c3 by playing d3... built directly.
    GoPosition g = new GoPosition(5, 5);
    // Black: b3, c2, c4 ; White: d2, d4, e3, c3 (White c3 in atari)
    g.setStone(pt("bc", 5), GoPosition.BLACK);
    g.setStone(pt("cb", 5), GoPosition.BLACK);
    g.setStone(pt("cd", 5), GoPosition.BLACK);
    g.setStone(pt("db", 5), GoPosition.WHITE);
    g.setStone(pt("dd", 5), GoPosition.WHITE);
    g.setStone(pt("ec", 5), GoPosition.WHITE);
    g.setStone(pt("cc", 5), GoPosition.WHITE);
    Stone[] before = toStones(g, 5);
    g.play(pt("dc", 5), GoPosition.BLACK); // captures c3: ko
    assertEquals(pt("cc", 5), g.koPoint());
    assertFalse(g.isBlackToMove());
    CanonicalPosition withKo = CanonicalPosition.of(g);
    CanonicalPosition noKo = CanonicalPosition.of(5, 5, g.copyCells(), false, -1);
    assertNotEquals(withKo.key(), noKo.key());
    int[] last = {3, 2};
    CanonicalPosition fromBoard =
        BoardPositionAdapter.canonical(5, 5, toStones(g, 5), before, Optional.of(last), false);
    assertEquals(withKo.key(), fromBoard.key());
  }

  private static Stone[] toStones(GoPosition g, int size) {
    Stone[] s = new Stone[size * size];
    for (int x = 0; x < size; x++)
      for (int y = 0; y < size; y++) {
        byte c = g.get(x + y * size);
        s[x * size + y] =
            c == GoPosition.BLACK ? Stone.BLACK : c == GoPosition.WHITE ? Stone.WHITE : Stone.EMPTY;
      }
    return s;
  }

  @Test
  void boardAdapterMatchesIndexedPositionsForTheAppLayout() throws IOException {
    OpeningDatabase db = db();
    importInto(db, "pro", false, write("(;SZ[13:9]PB[a];B[cc];W[jg];B[kc])"));
    GoPosition g = new GoPosition(13, 9);
    g.play(SgfGameIndexer.parseMove("cc", 13, 9), GoPosition.BLACK);
    g.play(SgfGameIndexer.parseMove("jg", 13, 9), GoPosition.WHITE);
    Stone[] app = new Stone[13 * 9];
    for (int x = 0; x < 13; x++)
      for (int y = 0; y < 9; y++) {
        byte c = g.get(x + y * 13);
        app[x * 9 + y] =
            c == GoPosition.BLACK ? Stone.BLACK : c == GoPosition.WHITE ? Stone.WHITE : Stone.EMPTY;
      }
    CanonicalPosition p =
        BoardPositionAdapter.canonical(13, 9, app, null, Optional.of(new int[] {9, 6}), true);
    OpeningStats s = db.query(p.key(), OpeningFilter.ALL, false);
    assertEquals(1, s.games());
    assertEquals(
        SgfGameIndexer.parseMove("kc", 13, 9), p.toDisplay(s.moves().get(0).canonicalMove()));
  }

  // ------------------------------------------------------------------ persistence, ZIP, scale

  @Test
  void databaseSurvivesReopenAndZipImportWorks() throws IOException {
    Path zip = tmp.resolve("games.zip");
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
      for (int i = 0; i < 3; i++) {
        out.putNextEntry(new ZipEntry("../evil/dir/g" + i + ".sgf"));
        out.write(game("z" + i, 19, "pd", i == 0 ? "dd" : "dp").getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
      out.putNextEntry(new ZipEntry("readme.txt"));
      out.write("not sgf".getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
    OpeningDatabase db = db();
    OpeningImporter.Report r = importInto(db, "zip", false, zip, write("garbage ( ;B[zz"));
    assertEquals(3, r.gamesAdded());
    assertEquals(1, r.failedGames());
    assertFalse(Files.exists(tmp.resolve("evil")));
    CanonicalPosition p = after(19, "pd");
    OpeningStats before = query(db, p);
    OpeningDatabase reopened = db();
    OpeningStats again = query(reopened, p);
    assertEquals(before, again);
    assertEquals(3, again.games());
    assertEquals("zip", reopened.collections().get(0).name());
  }

  @Test
  void manyGamesImportQuicklyAndSegmentsMerge() throws IOException {
    Random random = new Random(7);
    List<Path> files = new ArrayList<>();
    for (int i = 0; i < 1200; i++) {
      List<String> moves = new ArrayList<>();
      moves.add(i % 2 == 0 ? "pd" : "dp");
      for (int m = 0; m < 150; m++) moves.add(sgfPoint(random.nextInt(361), 19));
      files.add(write(game("r" + i, 19, moves.toArray(new String[0]))));
    }
    OpeningDatabase db = db();
    long start = System.nanoTime();
    OpeningImporter.Report r = importInto(db, "big", false, files.toArray(new Path[0]));
    long millis = (System.nanoTime() - start) / 1_000_000;
    assertEquals(1200, r.gamesAdded() + r.failedGames());
    assertTrue(millis < 60_000, "import took " + millis + " ms");
    OpeningStats s = query(db, after(19));
    assertEquals(r.gamesAdded(), s.games());
    assertEquals(1, s.moves().size()); // pd and dp are the same first move
    // Ten small imports force a segment merge; statistics must not change.
    for (int i = 0; i < 10; i++) importInto(db, "small" + i, false, write(game("s" + i, 19, "dd")));
    OpeningStats merged = query(db, after(19));
    assertEquals(r.gamesAdded() + 10, merged.games());
    assertEquals(merged, query(db(), after(19)));
  }

  @Test
  void cancelledImportKeepsCommittedGames() throws IOException {
    OpeningDatabase db = db();
    int id = db.createCollection("c");
    int[] calls = {0};
    OpeningImporter.Report r =
        OpeningImporter.importFiles(
            db,
            List.of(write(game("a", 19, "pd")), write(game("b", 19, "dd"))),
            id,
            false,
            (a, b, c, d) -> {},
            () -> calls[0]++ >= 1);
    assertTrue(r.cancelled());
    assertEquals(1, r.gamesAdded());
    assertEquals(1, db().gameCount());
  }
}
