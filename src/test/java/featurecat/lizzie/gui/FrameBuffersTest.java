package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Copying only where the spare image is stale gives the same frames as copying everything. */
class FrameBuffersTest {
  private static final int W = 120;
  private static final int H = 90;

  @Test
  void incrementalFramesMatchFullCopies() {
    FrameBuffers buffers = new FrameBuffers();
    Random random = new Random(5);
    BufferedImage shown = null;
    BufferedImage reference = null;
    for (int frame = 0; frame < 40; frame++) {
      boolean full = frame % 9 == 0;
      Rectangle dirty =
          new Rectangle(random.nextInt(W / 2), random.nextInt(H / 2), 10 + random.nextInt(50), 10);
      Color color = new Color(random.nextInt(), true);
      BufferedImage next;
      if (full) {
        next = buffers.forFullRedraw(shown, W, H);
        reference = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        paint(next, new Rectangle(0, 0, W, H), color);
        paint(reference, new Rectangle(0, 0, W, H), color);
      } else {
        next = buffers.forIncremental(shown, W, H, dirty);
        assertNotSame(shown, next, "drawn into the spare image, not the shown one");
        paint(next, dirty, color);
        paint(reference, dirty, color);
      }
      shown = next;
      assertArrayEquals(pixels(reference), pixels(shown), "frame " + frame);
    }
  }

  @Test
  void aFrameFromElsewhereIsCopiedWhole() {
    FrameBuffers buffers = new FrameBuffers();
    BufferedImage foreign = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
    paint(foreign, new Rectangle(0, 0, W, H), Color.ORANGE);
    BufferedImage next = buffers.forIncremental(foreign, W, H, new Rectangle(1, 1, 2, 2));
    assertArrayEquals(pixels(foreign), pixels(next));
  }

  private static void paint(BufferedImage image, Rectangle area, Color color) {
    Graphics2D g = image.createGraphics();
    g.setColor(color);
    g.fill(area);
    g.dispose();
  }

  private static int[] pixels(BufferedImage image) {
    return image.getRGB(0, 0, W, H, null, 0, W);
  }
}
