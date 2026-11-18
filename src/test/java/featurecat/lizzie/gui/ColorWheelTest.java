package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class ColorWheelTest {
  @Test
  void ringPicksHueAndDiscPicksSaturationAndBrightness() {
    ColorWheel w = new ColorWheel(new Color(0x3478DC));
    w.setSize(ColorWheel.SIZE, ColorWheel.SIZE);
    int c = ColorWheel.SIZE / 2;
    int ring = (int) (ColorWheel.SIZE * 0.445);
    assertTrue(w.isOnRing(c, c - ring));
    assertFalse(w.isOnRing(c, c));
    assertEquals(0f, w.hueAt(c, c - ring), 0.01f); // top = red
    assertEquals(0.25f, w.hueAt(c + ring, c), 0.01f); // right = yellow-green
    assertEquals(0.5f, w.hueAt(c, c + ring), 0.01f); // bottom = cyan
    float[] centre = w.svAt(c, c);
    assertEquals(0.5f, centre[0], 0.02f);
    assertEquals(0.5f, centre[1], 0.02f);
    float[] topRight = w.svAt(c + 200, c - 200);
    assertEquals(1f, topRight[0], 1e-6f); // clamped: full saturation and brightness
    assertEquals(1f, topRight[1], 1e-6f);
  }

  @Test
  void pickingUpdatesTheColourAndNotifiesListeners() {
    ColorWheel w = new ColorWheel(Color.RED);
    w.setSize(ColorWheel.SIZE, ColorWheel.SIZE);
    Color[] seen = {null};
    w.addChangeListener(col -> seen[0] = col);
    int c = ColorWheel.SIZE / 2;
    int ring = (int) (ColorWheel.SIZE * 0.445);
    w.mousePressedForTest(c, c + ring); // ring at the bottom: cyan hue
    float[] hsb = Color.RGBtoHSB(seen[0].getRed(), seen[0].getGreen(), seen[0].getBlue(), null);
    assertEquals(0.5f, hsb[0], 0.02f);
    w.mousePressedForTest(c, c); // disc centre
    hsb =
        Color.RGBtoHSB(
            w.getColor().getRed(), w.getColor().getGreen(), w.getColor().getBlue(), null);
    assertEquals(0.5f, hsb[1], 0.03f);
    assertEquals(0.5f, hsb[2], 0.03f);
    assertEquals("#FF0000", ColorWheel.hex(Color.RED));
    BufferedImage img =
        new BufferedImage(ColorWheel.SIZE, ColorWheel.SIZE, BufferedImage.TYPE_INT_ARGB);
    w.paint(img.createGraphics()); // paints headlessly without errors
  }
}
