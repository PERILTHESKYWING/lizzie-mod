package featurecat.lizzie.gui;

import featurecat.lizzie.theme.Theme;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The stone images of a theme, scaled and cached per size. A theme can ship several images per
 * colour (each clamshell stone has its own growth lines); every board point always gets the same
 * one. It also paints the theme's soft light falloff from the upper left over board and stones.
 */
final class ThemedStones {
  /** Warm dark tone the falloff fades toward, so it dims without greying the wood. */
  private static final Color FALLOFF_TONE = new Color(30, 18, 6);

  private final List<BufferedImage> black;
  private final List<BufferedImage> white;
  private final double falloff;
  private final Map<Long, BufferedImage> scaled = new HashMap<>();

  ThemedStones(List<BufferedImage> black, List<BufferedImage> white, double falloff) {
    this.black = black;
    this.white = white;
    this.falloff = Math.max(0, Math.min(0.3, falloff));
  }

  static ThemedStones of(Theme theme) {
    List<BufferedImage> b = theme.stoneVariants(true);
    List<BufferedImage> w = theme.stoneVariants(false);
    return new ThemedStones(
        b.isEmpty() ? List.of(theme.blackStone()) : b,
        w.isEmpty() ? List.of(theme.whiteStone()) : w,
        theme.lightFalloff());
  }

  int variants(boolean isBlack) {
    return (isBlack ? black : white).size();
  }

  /** The variant used at board point (x, y); stable for the point. */
  static int variantFor(int x, int y, int count) {
    return count <= 1 ? 0 : Math.floorMod(x * 7 + y * 13, count);
  }

  BufferedImage stone(boolean isBlack, int size, int variant) {
    List<BufferedImage> list = isBlack ? black : white;
    int v = Math.floorMod(variant, list.size());
    long key = ((long) size << 20) | ((long) v << 1) | (isBlack ? 1 : 0);
    return scaled.computeIfAbsent(key, k -> scale(list.get(v), size));
  }

  BufferedImage stoneAt(boolean isBlack, int size, int x, int y) {
    return stone(isBlack, size, variantFor(x, y, variants(isBlack)));
  }

  /**
   * The stone image at {@code size} pixels, area-averaged like {@code SCALE_SMOOTH} but computed by
   * {@link AreaScaler}, which is many times faster when a window is resized.
   */
  static BufferedImage scale(BufferedImage img, int size) {
    if (size <= img.getWidth() && size <= img.getHeight()) return AreaScaler.scale(img, size, size);
    return scaleSmooth(img, size); // enlarging: rare, keep the old path
  }

  /** Area-averaged scaling ({@code SCALE_SMOOTH}): exact but slow on large images. */
  static BufferedImage scaleSmooth(Image img, int size) {
    BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = out.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    g.drawImage(img.getScaledInstance(size, size, Image.SCALE_SMOOTH), 0, 0, null);
    g.dispose();
    return out;
  }

  double falloff() {
    return falloff;
  }

  /** Alpha of the falloff tone at (x, y) on a board of the given size: 0 at top left. */
  static int falloffAlpha(double strength, int x, int y, int width, int height) {
    double t = (x + y) / (double) Math.max(1, width + height);
    return (int) Math.round(255 * strength * Math.max(0, Math.min(1, t)));
  }

  /** Dims a stone like the board under it, so stones in the far corner do not float. */
  void paintStoneFalloff(Graphics2D g, int cx, int cy, int radius, int width, int height) {
    if (falloff <= 0) return;
    int alpha = falloffAlpha(falloff, cx, cy, width, height);
    if (alpha <= 0) return;
    g.setColor(
        new Color(FALLOFF_TONE.getRed(), FALLOFF_TONE.getGreen(), FALLOFF_TONE.getBlue(), alpha));
    g.fillOval(cx - radius, cy - radius, 2 * radius + 1, 2 * radius + 1);
  }

  /** The soft light falloff across the board, drawn over the wood texture. */
  static void paintBoardFalloff(Graphics2D g, int width, int height, double strength) {
    if (strength <= 0 || width <= 0 || height <= 0) return;
    int end = falloffAlpha(Math.min(0.3, strength), width, height, width, height);
    Paint old = g.getPaint();
    Color c = FALLOFF_TONE;
    g.setPaint(
        new GradientPaint(
            0,
            0,
            new Color(c.getRed(), c.getGreen(), c.getBlue(), 0),
            width,
            height,
            new Color(c.getRed(), c.getGreen(), c.getBlue(), end)));
    g.fillRect(0, 0, width, height);
    g.setPaint(old);
  }
}
