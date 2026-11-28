package featurecat.lizzie.gui;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

/**
 * Keeps a copy of the main panel's frame image in video memory and shows that.
 *
 * <p>The frame is drawn in an ordinary image; showing it made Java2D upload the whole image to the
 * graphics card on every repaint, which grows with the window's pixel count (tens of milliseconds a
 * frame on a large or high-DPI screen) even when only a stone or a label changed. Here only the
 * regions reported as changed are uploaded; repaints that change nothing upload nothing. When the
 * video memory copy is lost (a display change, a driver reset) it is filled again from the frame.
 * Without hardware acceleration the frame is drawn directly, as before.
 */
final class ScreenFrame {
  private VolatileImage vram;

  /** The frame image the copy follows; another image means a full upload. */
  private BufferedImage frame;

  /** Region of {@link #frame} not yet uploaded; null for all of it. */
  private Rectangle pending;

  /** Whether video memory copies turned out not to be accelerated here. */
  private boolean unaccelerated;

  /**
   * Reports that {@code image} is now the frame and differs from the previous frame only inside
   * {@code dirty} (null: anywhere). Invariant: the video memory copy equals {@link #frame} except
   * inside {@link #pending}.
   */
  void changed(BufferedImage image, Rectangle dirty) {
    pending = dirty == null || pending == null ? null : pending.union(dirty);
    frame = image;
  }

  /** Draws {@code image} at the origin, through the video memory copy when possible. */
  void draw(Graphics2D g, BufferedImage image) {
    if (image == null) return;
    if (image != frame) changed(image, null); // changed without being reported
    if (unaccelerated || !upload(g.getDeviceConfiguration())) {
      g.drawImage(image, 0, 0, null);
      return;
    }
    g.drawImage(vram, 0, 0, null);
    if (vram.contentsLost()) pending = null;
  }

  /** Brings the video memory copy up to date; false when it cannot be used. */
  private boolean upload(GraphicsConfiguration gc) {
    if (gc == null) return false;
    int w = frame.getWidth();
    int h = frame.getHeight();
    if (vram == null || vram.getWidth() != w || vram.getHeight() != h) {
      if (vram != null) vram.flush();
      vram = gc.createCompatibleVolatileImage(w, h, Transparency.TRANSLUCENT);
      pending = null;
      if (vram == null || !vram.getCapabilities().isAccelerated()) {
        unaccelerated = true;
        if (vram != null) vram.flush();
        vram = null;
        return false;
      }
    }
    int state = vram.validate(gc);
    if (state == VolatileImage.IMAGE_INCOMPATIBLE) {
      vram.flush();
      vram = null;
      return upload(gc);
    }
    if (state == VolatileImage.IMAGE_RESTORED) pending = null;
    Rectangle region =
        pending == null ? new Rectangle(0, 0, w, h) : pending.intersection(new Rectangle(w, h));
    if (!region.isEmpty()) {
      Graphics2D v = vram.createGraphics();
      v.setComposite(AlphaComposite.Src);
      int x1 = region.x + region.width;
      int y1 = region.y + region.height;
      // Naming the source rectangle uploads just that part (a clip alone uploads the whole image).
      v.drawImage(frame, region.x, region.y, x1, y1, region.x, region.y, x1, y1, null);
      v.dispose();
    }
    pending = new Rectangle();
    return !vram.contentsLost();
  }
}
