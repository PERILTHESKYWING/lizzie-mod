package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Stones' glass highlights and players' names are drawn from cached images; they must not look
 * different.
 */
class CachedSpritesLookTheSameTest {
  private static final Color WOOD = new Color(214, 170, 104);
  private static final Font FONT = new Font(Font.SANS_SERIF, Font.BOLD, 22);

  @Test
  void stoneHighlightsMatchPaintingThemDirectly() {
    for (int radius : new int[] {4, 9, 17, 26, 41}) {
      for (boolean black : new boolean[] {true, false}) {
        int r = radius;
        boolean b = black;
        BufferedImage direct =
            render(g -> GlassEffectRenderer.paintStoneHighlight(g, 60, 61, r, b), false);
        BufferedImage cached =
            render(g -> GlassEffectRenderer.drawStoneHighlight(g, 60, 61, r, b), false);
        assertTrue(maxDiff(direct, cached) <= 2, "radius " + r + (b ? " black" : " white"));
      }
    }
    assertSame(
        GlassEffectRenderer.highlightSprite(17, true),
        GlassEffectRenderer.highlightSprite(17, true),
        "painted once per size");
  }

  @Test
  void scaledGraphicsStillPaintSharpHighlights() {
    BufferedImage direct =
        render(g -> GlassEffectRenderer.paintStoneHighlight(g, 30, 30, 12, false), true);
    BufferedImage cached =
        render(g -> GlassEffectRenderer.drawStoneHighlight(g, 30, 30, 12, false), true);
    assertTrue(maxDiff(direct, cached) == 0, "no resampled sprite under a scale");
  }

  @Test
  void namesMatchPaintingTheirOutlinesDirectly() {
    double[][] spots = {{12, 40}, {12.3, 40.7}, {57.75, 81.5}};
    for (double[] at : spots) {
      for (boolean white : new boolean[] {true, false}) {
        boolean w = white;
        BufferedImage direct =
            render(
                g ->
                    BoardNamePlate.paintName(
                        g, BoardNamePlate.outline(g, FONT, "Honinbo Shusai", at[0], at[1]), w),
                false);
        BufferedImage cached =
            render(
                g -> {
                  if (w) BoardNamePlate.paintWhiteName(g, FONT, "Honinbo Shusai", at[0], at[1]);
                  else BoardNamePlate.paintBlackName(g, FONT, "Honinbo Shusai", at[0], at[1]);
                },
                false);
        assertTrue(
            maxDiff(direct, cached) <= 2, (w ? "white" : "black") + " at " + at[0] + "," + at[1]);
      }
    }
  }

  private static BufferedImage render(Consumer<Graphics2D> paint, boolean scaled) {
    BufferedImage image = new BufferedImage(240, 120, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    g.setColor(WOOD);
    g.fillRect(0, 0, 240, 120);
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    if (scaled) g.scale(2, 2);
    paint.accept(g);
    g.dispose();
    return image;
  }

  private static int maxDiff(BufferedImage a, BufferedImage b) {
    int max = 0;
    for (int y = 0; y < a.getHeight(); y++) {
      for (int x = 0; x < a.getWidth(); x++) {
        int p = a.getRGB(x, y);
        int q = b.getRGB(x, y);
        for (int shift = 0; shift < 32; shift += 8) {
          max = Math.max(max, Math.abs(((p >> shift) & 255) - ((q >> shift) & 255)));
        }
      }
    }
    return max;
  }
}
