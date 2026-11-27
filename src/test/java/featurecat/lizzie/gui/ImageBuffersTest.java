package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class ImageBuffersTest {
  @Test
  void aLayerOfTheSameSizeIsClearedAndReused() {
    BufferedImage layer = new BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = layer.createGraphics();
    g.setColor(Color.RED);
    g.fillRect(0, 0, 40, 30);
    g.dispose();

    BufferedImage again = ImageBuffers.blank(layer, 40, 30);

    assertSame(layer, again);
    for (int y = 0; y < 30; y++) {
      for (int x = 0; x < 40; x++) assertEquals(0, again.getRGB(x, y), x + "," + y);
    }
  }

  @Test
  void otherSizesOrTypesGetANewTransparentLayer() {
    BufferedImage layer = new BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB);
    BufferedImage bigger = ImageBuffers.blank(layer, 41, 30);
    assertNotSame(layer, bigger);
    assertEquals(41, bigger.getWidth());
    assertEquals(BufferedImage.TYPE_INT_ARGB, bigger.getType());
    BufferedImage rgb = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
    assertNotSame(rgb, ImageBuffers.blank(rgb, 40, 30));
    assertEquals(1, ImageBuffers.blank(null, 0, 0).getWidth());
  }

  @Test
  void onlyWholePixelTransformsCountAsAligned() {
    Graphics2D g = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB).createGraphics();
    assertTrue(ImageBuffers.pixelAligned(g));
    g.translate(3, 7);
    assertTrue(ImageBuffers.pixelAligned(g));
    g.translate(0.5, 0);
    assertFalse(ImageBuffers.pixelAligned(g));
    g.translate(-0.5, 0);
    g.scale(2, 2);
    assertFalse(ImageBuffers.pixelAligned(g));
    g.dispose();
  }
}
