package featurecat.lizzie.explorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Top-right naming, duplicate removal by moves, and Fox account import. */
class OpeningExplorerV2Test {
  @TempDir Path tmp;
  private int fileNo;

  private static int pt(String sgf) {
    return SgfGameIndexer.parseMove(sgf, 19, 19);
  }

  private static String sgfPoint(int index) {
    return "" + (char) ('a' + index % 19) + (char) ('a' + index / 19);
  }

  /** A game on distinct points from a seeded shuffle (no stone is played twice on a point). */
  private static List<String> moves(long seed, int count) {
    List<Integer> points = new ArrayList<>();
    for (int i = 0; i < 361; i++) points.add(i);
    java.util.Collections.shuffle(points, new Random(seed));
    List<String> result = new ArrayList<>();
    for (int i = 0; i < count; i++) result.add(sgfPoint(points.get(i)));
    return result;
  }

  private static String game(String black, String white, String date, List<String> moves) {
    StringBuilder sb =
        new StringBuilder("(;SZ[19]PB[" + black + "]PW[" + white + "]DT[" + date + "]");
    boolean b = true;
    for (String m : moves) {
      sb.append(';').append(b ? "B[" : "W[").append(m).append(']');
      b = !b;
    }
    return sb.append(')').toString();
  }

  private Path write(String sgf) throws IOException {
    Path p = tmp.resolve("g" + (fileNo++) + ".sgf");
    Files.writeString(p, sgf, StandardCharsets.UTF_8);
    return p;
  }

  private OpeningImporter.Report importInto(OpeningDatabase db, String collection, Path... files)
      throws IOException {
    return OpeningImporter.importFiles(
        db,
        List.of(files),
        db.createCollection(collection),
        false,
        (a, b, c, d) -> {},
        () -> false);
  }

  // ------------------------------------------------------------------ top-right naming

  @Test
  void equivalentPointsAreNamedInTheTopRightCorner() {
    CanonicalPosition empty = CanonicalPosition.of(new GoPosition(19, 19));
    for (String hoshi : new String[] {"dd", "pd", "dp", "pp"}) {
      assertEquals(pt("pd"), empty.toDisplay(empty.canonicalMove(pt(hoshi)))); // Q16
    }
    for (String komoku : new String[] {"dc", "cd", "qd", "pc", "cp", "dq", "pq", "qp"}) {
      assertEquals(pt("qd"), empty.toDisplay(empty.canonicalMove(pt(komoku)))); // R16
    }
    // A non-symmetric board still shows the real point.
    GoPosition g = new GoPosition(19, 19);
    g.play(pt("dp"), GoPosition.BLACK);
    g.play(pt("pd"), GoPosition.WHITE);
    g.play(pt("dc"), GoPosition.BLACK);
    CanonicalPosition p = CanonicalPosition.of(g);
    assertEquals(1, p.stabilizer().size());
    assertEquals(pt("pp"), p.toDisplay(p.canonicalMove(pt("pp"))));
  }

  // ------------------------------------------------------------------ duplicates by moves

  @Test
  void sameMovesWithDifferentNamesAreOneGame() throws IOException {
    OpeningDatabase db = OpeningDatabase.open(tmp.resolve("db"));
    List<String> long1 = moves(1, 120);
    OpeningImporter.Report r =
        importInto(
            db,
            "A",
            write(game("Ke Jie", "Shin", "2024-01-01", long1)),
            write(game("柯洁", "申真谞", "2024.1.1", long1)), // same game, other names and date
            write(game("x", "y", "", moves(2, 120))));
    assertEquals(2, r.gamesAdded());
    assertEquals(1, r.duplicates());
    assertEquals(2, db.gameCount());
    // Another collection importing the same moves links to the kept game.
    OpeningImporter.Report again = importInto(db, "B", write(game("z", "w", "", long1)));
    assertEquals(0, again.gamesAdded());
    assertEquals(1, again.duplicates());
    assertEquals(2, db.gameCount());
    for (OpeningDatabase.Collection c : db.collections()) {
      if (c.name().equals("B")) assertEquals(1, c.games());
    }
  }

  @Test
  void shortGamesAndDifferentGamesAreKept() throws IOException {
    OpeningDatabase db = OpeningDatabase.open(tmp.resolve("db"));
    List<String> shortGame = moves(3, 60);
    List<String> long1 = moves(4, 120);
    List<String> branch = new ArrayList<>(long1);
    branch.set(99, moves(5, 361).stream().filter(m -> !long1.contains(m)).findFirst().get());
    importInto(
        db,
        "A",
        write(game("a", "b", "", shortGame)),
        write(game("c", "d", "", shortGame)),
        write(game("e", "f", "", long1)),
        write(game("g", "h", "", branch)));
    assertEquals(4, db.gameCount());
  }

  @Test
  void gamesWhoseFirstHundredMovesMatchAreOneGameAndTheLongestIsKept() throws IOException {
    OpeningDatabase db = OpeningDatabase.open(tmp.resolve("db"));
    List<String> base = moves(8, 130);
    List<String> truncated = base.subList(0, 105); // same game, record stops early
    List<String> other = moves(9, 361);
    List<String> divergesAt100 = new ArrayList<>(base.subList(0, 99));
    other.stream().filter(m -> !base.contains(m)).limit(25).forEach(divergesAt100::add);
    OpeningImporter.Report r =
        importInto(
            db,
            "A",
            write(game("short copy", "w", "", truncated)),
            write(game("full copy", "w", "", base)),
            write(game("different 100th move", "w", "", divergesAt100)));
    assertEquals(2, db.gameCount());
    assertEquals(1, r.duplicates());
    CanonicalPosition empty = CanonicalPosition.of(new GoPosition(19, 19));
    List<String> players = new ArrayList<>();
    for (GameMetadata m : db.gamesAt(empty.key(), OpeningFilter.ALL, 10)) {
      players.add(m.blackPlayer());
    }
    assertTrue(players.contains("full copy"), players.toString()); // the longer record stays
    assertTrue(players.contains("different 100th move"));
  }

  @Test
  void mirroredCopiesOfTheSameRecordAreOneGame() throws IOException {
    OpeningDatabase db = OpeningDatabase.open(tmp.resolve("db"));
    List<String> long1 = moves(6, 120);
    List<String> mirrored = new ArrayList<>();
    for (String m : long1) mirrored.add(sgfPoint(BoardSymmetry.FLIP_X.mapIndex(pt(m), 19, 19)));
    importInto(db, "A", write(game("a", "b", "", long1)), write(game("c", "d", "", mirrored)));
    assertEquals(1, db.gameCount());
  }

  @Test
  void existingDatabasesAreCleanedOnceOnOpen() throws IOException {
    Path dir = tmp.resolve("old");
    OpeningDatabase db = OpeningDatabase.open(dir);
    int c = db.createCollection("A");
    List<String> long1 = moves(7, 120);
    for (String name : new String[] {"a", "b", "c"}) {
      db.addGame(
          SgfGameIndexer.index(SgfReader.parse(game(name, "w", "", long1)).get(0), name, false), c);
    }
    db.commit();
    assertEquals(3, db.gameCount());
    // Pretend the database was written before duplicate removal existed.
    Path library = dir.resolve("library.json");
    Files.writeString(
        library,
        Files.readString(library).replaceAll("\"dedupVersion\": ?\\d+", "\"dedupVersion\": 0"));
    OpeningDatabase reopened = OpeningDatabase.open(dir);
    assertEquals(1, reopened.gameCount());
    CanonicalPosition empty = CanonicalPosition.of(new GoPosition(19, 19));
    assertEquals(1, reopened.query(empty.key(), OpeningFilter.ALL, false).games());
    assertEquals(1, OpeningDatabase.open(dir).gameCount());
  }

  // ------------------------------------------------------------------ Fox import

  /** In-memory Fox: accounts → newest-first game ids → SGF, paged two at a time. */
  private static final class FakeFox implements OpeningImporter.FoxSource {
    final Map<String, String> uids = Map.of("alice", "111", "bob", "222");
    final Map<String, List<String>> games =
        Map.of("111", List.of("a5", "a4", "a3", "a2", "a1"), "222", List.of("b2", "b1"));
    int pageRequests;

    @Override
    public String findUid(String accountName) {
      return uids.getOrDefault(accountName, "");
    }

    @Override
    public List<String> gameIds(String uid, String after) {
      pageRequests++;
      List<String> all = games.get(uid);
      int start = after.equals("0") ? 0 : all.indexOf(after) + 1;
      return all.subList(Math.min(start, all.size()), Math.min(start + 2, all.size()));
    }

    @Override
    public String gameSgf(String gameId) {
      long seed = gameId.hashCode();
      return game("fox " + gameId, "opp", "2025-05-05", moves(seed, 40));
    }
  }

  @Test
  void foxAccountsImportTheirNewestGamesUpToTheLimit() throws IOException {
    OpeningDatabase db = OpeningDatabase.open(tmp.resolve("fox"));
    int c = db.createCollection("Fox");
    FakeFox fox = new FakeFox();
    OpeningImporter.Report r =
        OpeningImporter.importFox(
            db,
            List.of("alice", "bob", "nobody"),
            c,
            false,
            3,
            fox,
            (a, b, d, e) -> {},
            () -> false);
    assertEquals(5, r.gamesAdded()); // alice's 3 newest + bob's 2
    assertEquals(1, r.failedGames()); // "nobody" does not exist
    assertTrue(r.failures().get(0).contains("nobody"));
    assertEquals(5, db.gameCount());
    List<String> sources = new ArrayList<>();
    CanonicalPosition empty = CanonicalPosition.of(new GoPosition(19, 19));
    for (GameMetadata m : db.gamesAt(empty.key(), OpeningFilter.ALL, 10)) sources.add(m.source());
    assertTrue(sources.contains("fox:alice:a5"));
    assertTrue(!sources.contains("fox:alice:a2"));
  }

  @Test
  void foxImportCanBeCancelled() throws IOException {
    OpeningDatabase db = OpeningDatabase.open(tmp.resolve("fox2"));
    int c = db.createCollection("Fox");
    int[] polls = {0};
    OpeningImporter.Report r =
        OpeningImporter.importFox(
            db,
            List.of("alice"),
            c,
            false,
            200,
            new FakeFox(),
            (a, b, d, e) -> {},
            () -> polls[0]++ >= 2);
    assertTrue(r.cancelled());
    assertEquals(2, r.gamesAdded());
    assertEquals(2, OpeningDatabase.open(tmp.resolve("fox2")).gameCount());
  }

  @Test
  void colourSettingsSurviveARestartAndBadValuesFallBack() {
    OpeningExplorerSettings s = new OpeningExplorerSettings();
    s.markColor = OpeningExplorerSettings.hex(new java.awt.Color(10, 200, 30));
    s.lastFoxAccounts = "alice, bob";
    s.save(tmp);
    OpeningExplorerSettings loaded = OpeningExplorerSettings.load(tmp);
    assertEquals("#0AC81E", loaded.markColor);
    assertEquals("alice, bob", loaded.lastFoxAccounts);
    assertEquals(
        new java.awt.Color(10, 200, 30),
        OpeningExplorerSettings.color(loaded.markColor, OpeningExplorerSettings.DEFAULT_MARK));
    assertEquals(
        OpeningExplorerSettings.color(OpeningExplorerSettings.DEFAULT_BAR, "#000000"),
        OpeningExplorerSettings.color("not a colour", OpeningExplorerSettings.DEFAULT_BAR));
  }
}
