package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Per-point stone variants and the soft light falloff of the realistic themes. */
class ThemedStonesTest {
  private static BufferedImage solid(Color c) {
    BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setColor(c);
    g.fillRect(0, 0, 64, 64);
    g.dispose();
    return img;
  }

  @Test
  void eachPointKeepsItsOwnVariantAndAllVariantsAreUsed() {
    Set<Integer> used = new HashSet<>();
    for (int x = 0; x < 19; x++) {
      for (int y = 0; y < 19; y++) {
        int v = ThemedStones.variantFor(x, y, 8);
        assertEquals(v, ThemedStones.variantFor(x, y, 8));
        used.add(v);
      }
    }
    assertEquals(8, used.size());
    assertTrue(ThemedStones.variantFor(3, 4, 8) != ThemedStones.variantFor(4, 4, 8));
    assertEquals(0, ThemedStones.variantFor(5, 5, 1));
  }

  @Test
  void scaledStonesAreCachedPerSizeAndVariant() {
    ThemedStones stones =
        new ThemedStones(
            List.of(solid(Color.BLACK)), List.of(solid(Color.WHITE), solid(Color.LIGHT_GRAY)), 0);
    BufferedImage a = stones.stone(false, 40, 0);
    assertSame(a, stones.stone(false, 40, 0));
    assertNotSame(a, stones.stone(false, 40, 1));
    assertEquals(41, stones.stone(true, 41, 3).getWidth());
    assertEquals(2, stones.variants(false));
    assertEquals(1, stones.variants(true));
  }

  @Test
  void falloffDarkensTowardTheLowerRightOnly() {
    assertEquals(0, ThemedStones.falloffAlpha(0.05, 0, 0, 900, 900));
    assertEquals(13, ThemedStones.falloffAlpha(0.05, 900, 900, 900, 900));
    BufferedImage board = solid(new Color(240, 180, 100));
    Graphics2D g = board.createGraphics();
    ThemedStones.paintBoardFalloff(g, 64, 64, 0.05);
    g.dispose();
    int topLeft = board.getRGB(0, 0) & 0xFF;
    int bottomRight = board.getRGB(63, 63) & 0xFF;
    assertEquals(100, topLeft);
    assertTrue(bottomRight < topLeft && bottomRight >= 90, "a soft dimming, not a shadow");

    BufferedImage plain = solid(Color.WHITE);
    Graphics2D g2 = plain.createGraphics();
    new ThemedStones(List.of(plain), List.of(plain), 0).paintStoneFalloff(g2, 60, 60, 4, 64, 64);
    g2.dispose();
    assertEquals(Color.WHITE.getRGB(), plain.getRGB(60, 60), "themes without falloff unchanged");
  }
}
