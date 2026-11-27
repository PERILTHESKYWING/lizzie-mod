package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The fast stone scaling must look like the old {@code SCALE_SMOOTH} scaling. */
class ThemedStonesScaleTest {
  @Test
  void fastScalingIsAtLeastAsFaithfulAsSmoothScaling() {
    BufferedImage stone = stone(512);
    for (int size : new int[] {13, 22, 31, 47, 64, 97, 130, 200}) {
      BufferedImage fast = ThemedStones.scale(stone, size);
      assertEquals(size, fast.getWidth());
      assertEquals(size, fast.getHeight());
      assertEquals(0, fast.getRGB(0, 0) >>> 24, "corners stay transparent at " + size);
      double[] truth = areaAverage(stone, size);
      double[] mine = diff(fast, truth);
      double[] old = diff(ThemedStones.scaleSmooth(stone, size), truth);
      System.out.println(
          "SCALEDIFF "
              + size
              + " fast "
              + mine[0]
              + "/"
              + mine[1]
              + " old "
              + old[0]
              + "/"
              + old[1]);

      assertTrue(mine[0] <= old[0] + 0.05, "mean " + mine[0] + " vs " + old[0] + " at " + size);
      assertTrue(mine[1] <= old[1] + 1, "largest " + mine[1] + " vs " + old[1] + " at " + size);
    }
  }

  /** Exact premultiplied area averaging: what an ideal downscale shows on the board. */
  private static double[] areaAverage(BufferedImage src, int size) {
    int n = src.getWidth();
    double scale = (double) n / size;
    double[] out = new double[size * size * 4];
    for (int oy = 0; oy < size; oy++) {
      for (int ox = 0; ox < size; ox++) {
        double[] acc = new double[4];
        double y0 = oy * scale, y1 = y0 + scale, x0 = ox * scale, x1 = x0 + scale;
        for (int y = (int) y0; y < Math.min(n, Math.ceil(y1)); y++) {
          double wy = Math.min(y + 1, y1) - Math.max(y, y0);
          for (int x = (int) x0; x < Math.min(n, Math.ceil(x1)); x++) {
            double w = wy * (Math.min(x + 1, x1) - Math.max(x, x0));
            int p = src.getRGB(x, y);
            double a = (p >>> 24) / 255.0;
            acc[0] += w * a * 255;
            acc[1] += w * a * ((p >> 16) & 255);
            acc[2] += w * a * ((p >> 8) & 255);
            acc[3] += w * a * (p & 255);
          }
        }
        for (int c = 0; c < 4; c++) out[(oy * size + ox) * 4 + c] = acc[c] / (scale * scale);
      }
    }
    return out;
  }

  /** A shaded, slightly grainy stone on a transparent background, like the theme images. */
  private static BufferedImage stone(int size) {
    BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setPaint(
        new RadialGradientPaint(
            size * 0.35f,
            size * 0.3f,
            size * 0.7f,
            new float[] {0f, 0.5f, 1f},
            new Color[] {Color.WHITE, new Color(222, 220, 212), new Color(150, 148, 140)},
            MultipleGradientPaint.CycleMethod.NO_CYCLE));
    g.fillOval(4, 4, size - 8, size - 8);
    g.dispose();
    Random random = new Random(3);
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        int p = img.getRGB(x, y);
        if (p >>> 24 == 0) continue;
        int n = random.nextInt(9) - 4;
        int r = Math.max(0, Math.min(255, ((p >> 16) & 255) + n));
        int gr = Math.max(0, Math.min(255, ((p >> 8) & 255) + n));
        int b = Math.max(0, Math.min(255, (p & 255) + n));
        img.setRGB(x, y, (p & 0xff000000) | (r << 16) | (gr << 8) | b);
      }
    }
    return img;
  }

  /** Mean and largest difference of the premultiplied channels (what is seen on the board). */
  private static double[] diff(BufferedImage a, double[] truth) {
    double sum = 0;
    double max = 0;
    int size = a.getWidth();
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        int p = a.getRGB(x, y);
        double alpha = (p >>> 24) / 255.0;
        double[] mine = {
          alpha * 255, alpha * ((p >> 16) & 255), alpha * ((p >> 8) & 255), alpha * (p & 255)
        };
        for (int c = 0; c < 4; c++) {
          double d = Math.abs(mine[c] - truth[(y * size + x) * 4 + c]);
          sum += d;
          max = Math.max(max, d);
        }
      }
    }
    return new double[] {sum / (size * size * 4), max};
  }
}
