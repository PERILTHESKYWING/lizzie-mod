package featurecat.lizzie.gui;

import java.awt.image.BufferedImage;

/**
 * Exact area-averaging downscale (what {@code Image.SCALE_SMOOTH} computes), done as two separable
 * passes over premultiplied pixels. {@code getScaledInstance} streams every pixel through an image
 * filter pipeline and was a visible share of redrawing a board after a resize; this gives the same
 * result many times faster.
 */
final class AreaScaler {
  private AreaScaler() {}

  /** {@code src} scaled to {@code width x height}, as TYPE_INT_ARGB. Only shrinks; see below. */
  static BufferedImage scale(BufferedImage src, int width, int height) {
    int sw = src.getWidth();
    int sh = src.getHeight();
    width = Math.max(1, width);
    height = Math.max(1, height);
    int[] pixels = argbPixels(src);
    Span[] cols = spans(sw, width);
    Span[] rows = spans(sh, height);
    // Horizontal pass: premultiplied channels, rows of the source, columns of the result.
    float[] mid = new float[width * sh * 4];
    for (int y = 0; y < sh; y++) {
      int rowStart = y * sw;
      for (int x = 0; x < width; x++) {
        Span s = cols[x];
        float a = 0, r = 0, g = 0, b = 0;
        for (int i = 0; i < s.weights.length; i++) {
          int p = pixels[rowStart + s.first + i];
          float wa = s.weights[i] * (p >>> 24);
          a += wa;
          r += wa * ((p >> 16) & 255);
          g += wa * ((p >> 8) & 255);
          b += wa * (p & 255);
        }
        int o = (y * width + x) * 4;
        mid[o] = a;
        mid[o + 1] = r;
        mid[o + 2] = g;
        mid[o + 3] = b;
      }
    }
    int[] out = new int[width * height];
    for (int y = 0; y < height; y++) {
      Span s = rows[y];
      for (int x = 0; x < width; x++) {
        float a = 0, r = 0, g = 0, b = 0;
        for (int i = 0; i < s.weights.length; i++) {
          int o = ((s.first + i) * width + x) * 4;
          float w = s.weights[i];
          a += w * mid[o];
          r += w * mid[o + 1];
          g += w * mid[o + 2];
          b += w * mid[o + 3];
        }
        out[y * width + x] = a <= 0 ? 0 : pack(a, r / a, g / a, b / a);
      }
    }
    BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    result.getRaster().setDataElements(0, 0, width, height, out);
    return result;
  }

  /** The pixels as non-premultiplied ARGB ints; {@code getRGB} is slow for PNG image types. */
  private static int[] argbPixels(BufferedImage src) {
    BufferedImage argb = src;
    if (src.getType() != BufferedImage.TYPE_INT_ARGB) {
      argb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
      java.awt.Graphics2D g = argb.createGraphics();
      g.setComposite(java.awt.AlphaComposite.Src);
      g.drawImage(src, 0, 0, null);
      g.dispose();
    }
    int[] pixels = new int[argb.getWidth() * argb.getHeight()];
    argb.getRaster().getDataElements(0, 0, argb.getWidth(), argb.getHeight(), pixels);
    return pixels;
  }

  private static int pack(float a, float r, float g, float b) {
    return (clamp(a) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
  }

  private static int clamp(float v) {
    return Math.max(0, Math.min(255, Math.round(v)));
  }

  /** The source pixels covering one result pixel and how much of each it covers (sums to 1). */
  private record Span(int first, float[] weights) {}

  private static Span[] spans(int source, int target) {
    Span[] spans = new Span[target];
    double step = (double) source / target;
    for (int i = 0; i < target; i++) {
      double from = i * step;
      double to = Math.min(source, from + step);
      int first = (int) Math.floor(from);
      int last = Math.max(first, (int) Math.ceil(to) - 1);
      float[] w = new float[last - first + 1];
      for (int p = first; p <= last; p++) {
        w[p - first] = (float) ((Math.min(p + 1, to) - Math.max(p, from)) / (to - from));
      }
      spans[i] = new Span(first, w);
    }
    return spans;
  }
}
