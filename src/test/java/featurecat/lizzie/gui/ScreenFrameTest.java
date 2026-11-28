package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

/** The frame always shows exactly the current image, with or without video memory. */
class ScreenFrameTest {
  @Test
  void showsTheCurrentFrameAfterReportedAndUnreportedChanges() {
    ScreenFrame screen = new ScreenFrame();
    BufferedImage a = frame(Color.RED);
    BufferedImage b = frame(Color.RED);
    assertShows(screen, a);
    paint(b, new Rectangle(5, 5, 10, 10), Color.BLUE);
    screen.changed(b, new Rectangle(5, 5, 10, 10));
    assertShows(screen, b);
    paint(a, new Rectangle(0, 0, 40, 30), Color.GREEN);
    assertShows(screen, a); // a different image without a report is shown whole
  }

  private static void assertShows(ScreenFrame screen, BufferedImage image) {
    BufferedImage target = new BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = target.createGraphics();
    screen.draw(g, image);
    g.dispose();
    BufferedImage expected = new BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB);
    Graphics2D e = expected.createGraphics();
    e.drawImage(image, 0, 0, null);
    e.dispose();
    assertArrayEquals(
        expected.getRGB(0, 0, 40, 30, null, 0, 40), target.getRGB(0, 0, 40, 30, null, 0, 40));
  }

  private static BufferedImage frame(Color color) {
    BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB);
    paint(image, new Rectangle(0, 0, 40, 30), color);
    return image;
  }

  private static void paint(BufferedImage image, Rectangle area, Color color) {
    Graphics2D g = image.createGraphics();
    g.setColor(color);
    g.fill(area);
    g.dispose();
  }
}
