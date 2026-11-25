package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Zobrist;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Re-analysing after a komi or rule change redoes moves whose analysis is now stale. */
class ReanalyzeStaleMovesTest {
  @TempDir Path tmp;
  private Config previousConfig;
  private int previousWidth;
  private int previousHeight;

  @BeforeEach
  void setUp() {
    previousConfig = Lizzie.config;
    previousWidth = Board.boardWidth;
    previousHeight = Board.boardHeight;
    Lizzie.config = ConfigTestHelper.createForTests(tmp.resolve("work"));
    Lizzie.config.analysisAlwaysOverride = false;
    Board.boardWidth = 19;
    Board.boardHeight = 19;
    Zobrist.init();
  }

  @AfterEach
  void tearDown() {
    Lizzie.config = previousConfig;
    Board.boardWidth = previousWidth;
    Board.boardHeight = previousHeight;
    Zobrist.init();
  }

  @Test
  void staleAnalysisIsRedoneEvenWhenItHadEnoughVisits() throws Exception {
    BoardHistoryList history = SGFParser.parseSgf("(;SZ[19];B[pd];W[dd])", false);
    BoardHistoryNode move = history.getStart().next().orElseThrow();
    move.getData().setPlayouts(500);

    assertFalse(AnalysisEngine.shouldAnalyzeNode(move, 300), "fresh analysis is kept");

    move.getData().isChanged = true; // what a komi or rule change does to analysed moves
    assertTrue(AnalysisEngine.shouldAnalyzeNode(move, 300), "stale analysis is redone");

    move.getData().isChanged = false;
    move.getData().setPlayouts(10);
    assertTrue(AnalysisEngine.shouldAnalyzeNode(move, 300), "thin analysis is still topped up");
    assertFalse(AnalysisEngine.shouldAnalyzeNode(history.getStart(), 300), "the root is no move");
  }
}
