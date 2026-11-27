package featurecat.lizzie.gui;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * The two window-sized images the main panel alternates between: one is shown while the other is
 * drawn. An incremental frame (only the boards changed) starts from a copy of the shown frame; this
 * keeps track of where the spare image still differs from the shown one, so only that region
 * (usually the board from the frame before) is copied instead of the whole window, which on a large
 * or high-resolution screen is many megabytes per frame. The result is the same image.
 */
final class FrameBuffers {
  private BufferedImage a;
  private BufferedImage b;

  /** The image handed out last; the caller shows it. */
  private BufferedImage handedOut;

  /** Where the spare image differs from {@link #handedOut}; null when it may differ anywhere. */
  private Rectangle spareStale;

  /** A cleared spare image for a full redraw. */
  BufferedImage forFullRedraw(BufferedImage shown, int width, int height) {
    BufferedImage spare = spare(shown, width, height);
    Graphics2D g = spare.createGraphics();
    g.setComposite(AlphaComposite.Clear);
    g.fillRect(0, 0, width, height);
    g.dispose();
    handedOut = spare;
    spareStale = null;
    return spare;
  }

  /**
   * The spare image made equal to {@code shown}, for redrawing {@code dirty} (null: unknown) on top
   * of it.
   */
  BufferedImage forIncremental(BufferedImage shown, int width, int height, Rectangle dirty) {
    boolean known = shown == handedOut && spareStale != null;
    BufferedImage spare = spare(shown, width, height);
    Graphics2D g = spare.createGraphics();
    g.setComposite(AlphaComposite.Src);
    if (known && !spareIsNew) {
      if (!spareStale.isEmpty()) {
        g.setClip(spareStale);
        g.drawImage(shown, 0, 0, null);
      }
    } else {
      g.drawImage(shown, 0, 0, null);
    }
    g.dispose();
    handedOut = spare;
    spareStale = dirty == null ? null : new Rectangle(dirty);
    return spare;
  }

  /** Whether {@link #spare} just allocated the image (its content is then blank). */
  private boolean spareIsNew;

  private BufferedImage spare(BufferedImage shown, int width, int height) {
    boolean intoA = shown != a || a == null;
    BufferedImage spare = intoA ? a : b;
    spareIsNew = false;
    if (spare == null || spare.getWidth() != width || spare.getHeight() != height) {
      spare = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
      if (intoA) a = spare;
      else b = spare;
      spareIsNew = true;
    }
    return spare;
  }
}
