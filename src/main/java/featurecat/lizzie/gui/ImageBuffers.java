package featurecat.lizzie.gui;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/** Reusable transparent drawing layers: clearing one is much cheaper than allocating it anew. */
final class ImageBuffers {
  private ImageBuffers() {}

  /**
   * A fully transparent ARGB image of the given size: {@code current} cleared when it already has
   * that size, otherwise a new one. Board layers were reallocated on every move, which at 4 bytes a
   * pixel made the garbage collector pause noticeably on large boards.
   */
  static BufferedImage blank(BufferedImage current, int width, int height) {
    if (current != null
        && current.getWidth() == width
        && current.getHeight() == height
        && current.getType() == BufferedImage.TYPE_INT_ARGB) {
      Graphics2D g = current.createGraphics();
      g.setComposite(AlphaComposite.Clear);
      g.fillRect(0, 0, width, height);
      g.dispose();
      return current;
    }
    return new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
  }

  /**
   * Whether user space maps onto whole device pixels (no scaling, rotation or fractional shift), so
   * a cached sprite drawn there looks exactly like painting the shapes directly. Elsewhere the
   * sprite would be resampled, so callers paint the vector shapes instead.
   */
  static boolean pixelAligned(Graphics2D g) {
    AffineTransform t = g.getTransform();
    int type = t.getType();
    if (type == AffineTransform.TYPE_IDENTITY) return true;
    return type == AffineTransform.TYPE_TRANSLATION
        && t.getTranslateX() == Math.rint(t.getTranslateX())
        && t.getTranslateY() == Math.rint(t.getTranslateY());
  }
}
