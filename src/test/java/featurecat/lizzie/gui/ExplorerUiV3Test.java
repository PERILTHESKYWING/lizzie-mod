package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.explorer.GameMetadata;
import featurecat.lizzie.explorer.OpeningDatabase;
import java.awt.Component;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExplorerUiV3Test {
  @TempDir Path tmp;

  @Test
  void toolbarItemsAreHiddenByLabelAndStrandedSeparatorsGoToo() {
    TopHeaderPanel bar = new TopHeaderPanel();
    JButton fox = new JButton("Fox Kifu");
    JButton tencent = new JButton("Tencent");
    JPanel komi = new JPanel();
    komi.add(new JLabel("Komi:"));
    JButton rules = new JButton("Rules");
    bar.add(fox);
    bar.add(tencent);
    bar.addSeparator();
    bar.add(komi);
    bar.addSeparator();
    bar.add(rules);
    TopBarCustomization c = bar.customization();
    assertEquals(List.of("Fox Kifu", "Tencent", "Komi", "Rules"), c.choosableLabels(bar.content()));
    c.setHidden(Set.of("Fox Kifu", "Tencent"));
    c.apply(bar.content());
    assertFalse(fox.isVisible());
    assertFalse(tencent.isVisible());
    assertTrue(komi.isVisible());
    List<Component> items = TopBarCustomization.items(bar.content());
    assertFalse(items.get(2).isVisible()); // separator before Komi has nothing visible before it
    assertTrue(items.get(4).isVisible()); // separator between Komi and Rules stays
    c.setHidden(Set.of());
    c.apply(bar.content());
    assertTrue(fox.isVisible());
    assertTrue(items.get(2).isVisible());
  }

  @Test
  void toolbarChoiceIsSaved() {
    Path file = tmp.resolve(TopBarCustomization.FILE);
    new TopBarCustomization(file).setHidden(Set.of("OGS"));
    assertEquals(Set.of("OGS"), new TopBarCustomization(file).hiddenLabels());
  }

  @Test
  void candidateCellsShowTheMoveAndBars() {
    assertEquals("1  Q4", CandidateTableStyle.rankWithMove("1", "Q4"));
    assertEquals("1", CandidateTableStyle.rankWithMove("1", "pass"));
    assertEquals(77.7, CandidateTableStyle.leadingNumber("77.7(-0.3)"), 1e-9);
    assertTrue(Double.isNaN(CandidateTableStyle.leadingNumber("-")));
    java.awt.image.BufferedImage img =
        new java.awt.image.BufferedImage(60, 24, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    CandidateTableStyle.paintBars(
        img.createGraphics(), CandidateTableStyle.WINRATE_COLUMN, "77.7", 60, 24);
    assertTrue((img.getRGB(10, 24 - 5) >>> 24) > 0); // the bar is drawn near the bottom
  }

  @Test
  void filterValuesDropTheirCounts() {
    OpeningDatabase.Facets f =
        new OpeningDatabase.Facets(
            Map.of("柯洁", 12), Map.of("9段", 20), Map.of("LG杯", 3), Map.of(2024, 7), 30);
    OpeningFilterDialog d =
        new OpeningFilterDialog(f, new OpeningFilterDialog.Choice("", "", "", 0, 0), k -> k);
    d.player.setSelectedItem("柯洁  (12)");
    d.rank.setSelectedItem("9段  (20)");
    d.fromYear.setSelectedItem("2024  (7)");
    d.event.getEditor().setItem("lg");
    d.event.setSelectedItem("lg");
    OpeningFilterDialog.Choice c = d.read();
    assertEquals("柯洁", c.player());
    assertEquals("9段", c.rank());
    assertEquals("lg", c.event());
    assertEquals(2024, c.minYear());
    assertEquals(0, c.maxYear());
  }

  @Test
  void gamesWindowExplainsMissingRecords() {
    GameMetadata m =
        new GameMetadata("a", "b", "", "", "", "", "", "", "", 19, 19, "", Double.NaN, 0, "f.sgf");
    OpeningGamesWindow w =
        new OpeningGamesWindow(
            null,
            "t",
            List.of(new OpeningDatabase.GameRef(1, m, false)),
            new featurecat.lizzie.explorer.PositionKey(1, 2),
            (id, r) -> r.accept(null),
            (rec, moves) -> {},
            k -> k);
    OpeningGamesWindow.Lookup[] got = {null};
    w.snapshotFor(0, l -> got[0] = l);
    assertEquals("noRecord", w.caption(got[0]));
  }
}
