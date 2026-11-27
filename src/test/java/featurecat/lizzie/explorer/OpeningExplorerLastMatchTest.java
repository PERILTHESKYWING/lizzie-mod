package featurecat.lizzie.explorer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import featurecat.lizzie.rules.Stone;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The last position of a game found in the database, keyed off the event thread. */
class OpeningExplorerLastMatchTest {
  @TempDir Path tmp;

  @Test
  void findsTheLatestPositionThatHasGames() throws Exception {
    Path sgf = tmp.resolve("g.sgf");
    Files.writeString(sgf, "(;SZ[19]PB[a]PW[b];B[pd];W[dd];B[pp])", StandardCharsets.UTF_8);
    OpeningDatabase db = OpeningDatabase.open(tmp.resolve("db"));
    OpeningImporter.importFiles(
        db, List.of(sgf), db.createCollection("A"), false, (a, b, c, d) -> {}, () -> false);

    Stone[] empty = stones();
    Stone[] pd = stones("pd", Stone.BLACK);
    Stone[] pdDd = stones("pd", Stone.BLACK, "dd", Stone.WHITE);
    Stone[] offBook = stones("pd", Stone.BLACK, "dd", Stone.WHITE, "qq", Stone.BLACK);
    List<OpeningExplorerService.BoardPosition> newestFirst =
        List.of(
            position(new Object(), offBook, pdDd, "qq", false),
            position(new Object(), pdDd, pd, "dd", true),
            position(new Object(), pd, empty, "pd", false));

    try (OpeningExplorerService service =
        new OpeningExplorerService(tmp.resolve("db"), Runnable::run)) {
      assertArrayEquals(new int[] {1, 1}, lastMatch(service, newestFirst));
    }
  }

  @Test
  void keysAreRememberedPerNodeUntilItsStonesChange() throws Exception {
    try (OpeningExplorerService service =
        new OpeningExplorerService(tmp.resolve("db"), Runnable::run)) {
      Object node = new Object();
      Stone[] stones = stones("pd", Stone.BLACK);
      OpeningExplorerService.BoardPosition p = position(node, stones, stones(), "pd", false);
      PositionKey first = service.keyOf(19, 19, p);
      assertSame(first, service.keyOf(19, 19, p), "computed once");
      stones[index("dd")] = Stone.WHITE; // edited in place
      assertNotEquals(first, service.keyOf(19, 19, p), "an edited position is keyed again");
    }
  }

  private static int[] lastMatch(
      OpeningExplorerService service, List<OpeningExplorerService.BoardPosition> positions)
      throws Exception {
    CompletableFuture<int[]> found = new CompletableFuture<>();
    service.lastMatchOfPositions(19, 19, positions, OpeningFilter.ALL, false, found::complete);
    int[] result = found.get(10, TimeUnit.SECONDS);
    assertEquals(2, result.length);
    return result;
  }

  private static OpeningExplorerService.BoardPosition position(
      Object node, Stone[] stones, Stone[] parent, String move, boolean blackToPlay) {
    int i = index(move);
    return new OpeningExplorerService.BoardPosition(
        node, stones, parent, Optional.of(new int[] {i / 19, i % 19}), blackToPlay);
  }

  /** Column-major, like the application's boards. */
  private static int index(String sgf) {
    return (sgf.charAt(0) - 'a') * 19 + (sgf.charAt(1) - 'a');
  }

  private static Stone[] stones(Object... moves) {
    Stone[] s = new Stone[361];
    java.util.Arrays.fill(s, Stone.EMPTY);
    for (int i = 0; i < moves.length; i += 2) s[index((String) moves[i])] = (Stone) moves[i + 1];
    return s;
  }
}
