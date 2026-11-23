package featurecat.lizzie.explorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Stored records, replay to a position, facets for the filter lists, Fox event names. */
class OpeningExplorerV3Test {
  @TempDir Path tmp;
  private int n;

  private Path write(String sgf) throws IOException {
    Path p = tmp.resolve("g" + (n++) + ".sgf");
    Files.writeString(p, sgf, StandardCharsets.UTF_8);
    return p;
  }

  private OpeningDatabase seeded() throws IOException {
    OpeningDatabase db = OpeningDatabase.open(tmp.resolve("db"));
    OpeningImporter.importFiles(
        db,
        List.of(
            write("(;SZ[19]PB[柯洁]BR[9段]PW[申真谞]WR[9段]DT[2024-03-01]GN[LG杯];B[pd];W[dd];B[pp])"),
            write("(;SZ[19]PB[柯洁]BR[9段]PW[x]DT[2023.5.2]EV[Cup];B[dp];W[pp];B[dd])"),
            write("(;SZ[19]PB[y]PW[z]C[no meta];B[pd](;W[dp])(;W[dd]))")),
        db.createCollection("A"),
        false,
        (a, b, c, d) -> {},
        () -> false);
    return db;
  }

  @Test
  void recordsAreStoredAndSurviveReopen() throws IOException {
    OpeningDatabase db = seeded();
    CanonicalPosition afterPd = after("pd");
    List<OpeningDatabase.GameRef> refs =
        db.gamesAt(afterPd.key(), OpeningFilter.ALL, 10).isEmpty()
            ? List.of()
            : db.gamesPlaying(
                afterPd.key(), afterPd.canonicalMove(pt("dd")), OpeningFilter.ALL, false, 10);
    assertEquals(
        2, refs.size()); // game 1 directly, game 2 (dp pp dd) is the same position mirrored
    for (OpeningDatabase.GameRef r : refs) {
      assertTrue(r.hasRecord());
      String record = OpeningDatabase.open(tmp.resolve("db")).record(r.id());
      assertNotNull(record);
      assertTrue(record.startsWith("(;"));
      assertTrue(!record.contains("(;W[dp])")); // main line only
    }
  }

  @Test
  void replayFindsThePositionAndTheMovePlayedFromIt() {
    String record = "(;SZ[19]PB[a];B[dp];W[pp];B[dd];W[pd])";
    GameReplay.Snapshot s = GameReplay.find(record, after("pd", "dd").key()); // mirrored match
    assertNotNull(s);
    assertEquals(2, s.movesPlayed());
    assertEquals(pt("dd"), s.nextMove());
    GameReplay.Snapshot end = GameReplay.find(record, after("dp", "pp", "dd", "pd").key());
    assertEquals(CanonicalPosition.END, end.nextMove());
    assertNull(GameReplay.find(record, after("qq").key()));
    assertNull(GameReplay.find("not sgf", after().key()));
  }

  @Test
  void facetsListTheValuesFoundWithCounts() throws IOException {
    OpeningDatabase db = seeded();
    OpeningDatabase.Facets f = db.facets(Set.of());
    assertEquals(3, f.games());
    assertEquals(2, f.players().get("柯洁"));
    assertEquals("柯洁", f.players().keySet().iterator().next()); // most common first
    assertEquals(2, f.ranks().get("9段"));
    assertEquals(1, f.events().get("LG杯")); // Fox-style GN used when EV is missing
    assertEquals(List.of(2024, 2023), List.copyOf(f.years().keySet()));
    // The filter built from those values finds the games.
    CanonicalPosition empty = after();
    assertEquals(
        2, db.query(empty.key(), new OpeningFilter(Set.of(), "柯洁", "", "", 0, 0), false).games());
    assertEquals(
        2, db.query(empty.key(), new OpeningFilter(Set.of(), "", "9段", "", 0, 0), false).games());
    assertEquals(
        1, db.query(empty.key(), new OpeningFilter(Set.of(), "", "", "lg杯", 0, 0), false).games());
    assertEquals(
        1,
        db.query(empty.key(), new OpeningFilter(Set.of(), "", "", "", 2024, 2024), false).games());
  }

  private static int pt(String s) {
    return SgfGameIndexer.parseMove(s, 19, 19);
  }

  private static CanonicalPosition after(String... moves) {
    GoPosition g = new GoPosition(19, 19);
    byte c = GoPosition.BLACK;
    for (String m : moves) {
      g.play(pt(m), c);
      c = c == GoPosition.BLACK ? GoPosition.WHITE : GoPosition.BLACK;
    }
    return CanonicalPosition.of(g);
  }
}
