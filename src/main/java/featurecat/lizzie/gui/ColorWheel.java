package featurecat.lizzie.gui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * A colour picker drawn as a hue ring around a saturation/brightness disc: drag on the ring to pick
 * the hue, inside the disc to pick saturation (left to right) and brightness (bottom to top).
 */
final class ColorWheel extends JComponent {
  static final int SIZE = 220;
  private static final double RING_OUTER = 0.5;
  private static final double RING_INNER = 0.39;
  private static final double DISC = 0.33;

  private float hue;
  private float saturation;
  private float brightness;
  private BufferedImage ringImage;
  private BufferedImage discImage;
  private float discHue = -1;
  private boolean draggingRing;
  private final List<Consumer<Color>> listeners = new ArrayList<>();

  ColorWheel(Color initial) {
    setColor(initial);
    setPreferredSize(new Dimension(SIZE, SIZE));
    MouseAdapter mouse =
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            draggingRing = isOnRing(e.getX(), e.getY());
            pick(e.getX(), e.getY());
          }

          @Override
          public void mouseDragged(MouseEvent e) {
            pick(e.getX(), e.getY());
          }
        };
    addMouseListener(mouse);
    addMouseMotionListener(mouse);
  }

  void addChangeListener(Consumer<Color> listener) {
    listeners.add(listener);
  }

  Color getColor() {
    return Color.getHSBColor(hue, saturation, brightness);
  }

  void setColor(Color c) {
    float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
    hue = hsb[0];
    saturation = hsb[1];
    brightness = hsb[2];
    repaint();
  }

  // ------------------------------------------------------------------ geometry (tested)

  private double side() {
    return Math.min(getWidth() > 0 ? getWidth() : SIZE, getHeight() > 0 ? getHeight() : SIZE);
  }

  private double cx() {
    return (getWidth() > 0 ? getWidth() : SIZE) / 2.0;
  }

  private double cy() {
    return (getHeight() > 0 ? getHeight() : SIZE) / 2.0;
  }

  boolean isOnRing(int x, int y) {
    double r = Math.hypot(x - cx(), y - cy()) / side();
    return r >= RING_INNER - 0.03 && r <= RING_OUTER + 0.02;
  }

  /** Hue for a point, 0 at the top, increasing clockwise. */
  float hueAt(int x, int y) {
    double angle = Math.atan2(x - cx(), -(y - cy()));
    double h = angle / (2 * Math.PI);
    return (float) (h < 0 ? h + 1 : h);
  }

  /** Saturation and brightness for a point in the disc, clamped to the disc's square. */
  float[] svAt(int x, int y) {
    double half = DISC * side() / Math.sqrt(2);
    double s = ((x - cx()) + half) / (2 * half);
    double b = (half - (y - cy())) / (2 * half);
    return new float[] {(float) clamp(s), (float) clamp(b)};
  }

  private static double clamp(double v) {
    return Math.max(0, Math.min(1, v));
  }

  /** Same as a mouse press at (x, y); for tests. */
  void mousePressedForTest(int x, int y) {
    draggingRing = isOnRing(x, y);
    pick(x, y);
  }

  void pick(int x, int y) {
    if (draggingRing) {
      hue = hueAt(x, y);
    } else {
      float[] sv = svAt(x, y);
      saturation = sv[0];
      brightness = sv[1];
    }
    repaint();
    Color c = getColor();
    for (Consumer<Color> l : listeners) l.accept(c);
  }

  // ------------------------------------------------------------------ painting

  @Override
  protected void paintComponent(Graphics graphics) {
    Graphics2D g = (Graphics2D) graphics.create();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      int size = (int) side();
      int left = (int) (cx() - size / 2.0);
      int top = (int) (cy() - size / 2.0);
      if (ringImage == null || ringImage.getWidth() != size) ringImage = ring(size);
      g.drawImage(ringImage, left, top, null);
      if (discImage == null || discImage.getWidth() != size || discHue != hue) {
        discImage = disc(size, hue);
        discHue = hue;
      }
      g.drawImage(discImage, left, top, null);

      double ringMid = (RING_OUTER + RING_INNER) / 2 * size;
      double angle = hue * 2 * Math.PI;
      double kx = cx() + Math.sin(angle) * ringMid;
      double ky = cy() - Math.cos(angle) * ringMid;
      double kr = (RING_OUTER - RING_INNER) / 2 * size + 3;
      knob(g, kx, ky, kr, Color.getHSBColor(hue, 1f, 1f));

      double half = DISC * size / Math.sqrt(2);
      double sx = cx() - half + saturation * 2 * half;
      double sy = cy() + half - brightness * 2 * half;
      knob(g, sx, sy, 7, getColor());
    } finally {
      g.dispose();
    }
  }

  private static void knob(Graphics2D g, double x, double y, double r, Color fill) {
    Ellipse2D e = new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r);
    g.setColor(fill);
    g.fill(e);
    g.setStroke(new BasicStroke(2.5f));
    g.setColor(Color.WHITE);
    g.draw(e);
    g.setStroke(new BasicStroke(1f));
    g.setColor(new Color(0, 0, 0, 90));
    g.draw(new Ellipse2D.Double(x - r - 1.5, y - r - 1.5, 2 * r + 3, 2 * r + 3));
  }

  private static BufferedImage ring(int size) {
    BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    double c = size / 2.0;
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        double dx = x + 0.5 - c;
        double dy = y + 0.5 - c;
        double r = Math.hypot(dx, dy) / size;
        if (r < RING_INNER || r > RING_OUTER) continue;
        double h = Math.atan2(dx, -dy) / (2 * Math.PI);
        double edge = Math.min(r - RING_INNER, RING_OUTER - r) * size;
        int alpha = (int) Math.round(255 * Math.min(1, edge));
        int rgb = Color.HSBtoRGB((float) (h < 0 ? h + 1 : h), 1f, 1f) & 0xFFFFFF;
        img.setRGB(x, y, (alpha << 24) | rgb);
      }
    }
    return img;
  }

  /** The saturation/brightness square for {@code hue}, clipped to a circle. */
  private static BufferedImage disc(int size, float hue) {
    BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    double c = size / 2.0;
    double radius = DISC * size;
    double half = radius / Math.sqrt(2);
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        double dx = x + 0.5 - c;
        double dy = y + 0.5 - c;
        double d = Math.hypot(dx, dy);
        if (d > radius) continue;
        float s = (float) clamp((dx + half) / (2 * half));
        float b = (float) clamp((half - dy) / (2 * half));
        int alpha = (int) Math.round(255 * Math.min(1, radius - d));
        int rgb = Color.HSBtoRGB(hue, s, b) & 0xFFFFFF;
        img.setRGB(x, y, (alpha << 24) | rgb);
      }
    }
    return img;
  }

  // ------------------------------------------------------------------ dialog

  /** Shows the wheel with a preview and a hex field; returns the colour, or null on cancel. */
  static Color showDialog(Component parent, String title, Color initial, String ok, String cancel) {
    ColorWheel wheel = new ColorWheel(initial);
    JLabel preview = new JLabel();
    preview.setOpaque(true);
    preview.setPreferredSize(new Dimension(44, 26));
    preview.setBorder(BorderFactory.createLineBorder(Color.GRAY));
    preview.setBackground(initial);
    JTextField hex = new JTextField(hex(initial), 8);
    wheel.addChangeListener(
        c -> {
          preview.setBackground(c);
          hex.setText(hex(c));
        });
    hex.addActionListener(
        e -> {
          String t = hex.getText().trim();
          if (t.matches("#?[0-9A-Fa-f]{6}")) {
            Color c = new Color(Integer.parseInt(t.replace("#", ""), 16));
            wheel.setColor(c);
            preview.setBackground(c);
          }
        });
    JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
    bottom.add(preview);
    bottom.add(hex);
    JPanel panel = new JPanel(new BorderLayout(0, 6));
    panel.add(wheel, BorderLayout.CENTER);
    panel.add(bottom, BorderLayout.SOUTH);
    Object[] options = {ok, cancel};
    int answer =
        JOptionPane.showOptionDialog(
            parent,
            panel,
            title,
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.PLAIN_MESSAGE,
            null,
            options,
            options[0]);
    return answer == 0 ? wheel.getColor() : null;
  }

  static String hex(Color c) {
    return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
  }
}
