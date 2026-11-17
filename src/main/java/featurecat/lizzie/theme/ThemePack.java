package featurecat.lizzie.theme;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONObject;

/**
 * A declarative visual pack: a folder with {@code theme.txt} (the properties {@link Theme} already
 * reads), the image files it names, and an optional {@code pack.json} manifest describing the pack.
 *
 * <p>Packs never contain code. Validation keeps every image inside the pack folder, limits file
 * types and sizes, and reports unknown properties, so a future installer can refuse a pack before
 * copying it. Nothing here changes how the current themes are rendered.
 *
 * <p>Manifest ({@code pack.json}, schema 1):
 *
 * <pre>{@code
 * { "schemaVersion": 1, "id": "walnut", "name": "Walnut", "author": "...", "version": "1.0.0",
 *   "compatibleAppVersion": ">=2.5.0", "description": "...", "license": "CC-BY-4.0" }
 * }</pre>
 */
public final class ThemePack {
  public static final String MANIFEST = "pack.json";
  public static final String THEME_FILE = "theme.txt";
  static final long MAX_IMAGE_BYTES = 16L * 1024 * 1024;
  static final long MAX_TEXT_BYTES = 256L * 1024;

  /** Properties whose value is an image file inside the pack. */
  public static final Set<String> IMAGE_KEYS =
      Set.of("board-image", "background-image", "black-stone-image", "white-stone-image");

  private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif");

  /** Every property {@link Theme} reads from {@code theme.txt} (the supported extension points). */
  public static final Set<String> KNOWN_KEYS =
      Set.of(
          "background-filter",
          "background-image",
          "best-move-color",
          "black-stone-image",
          "blunder-bar-color",
          "blunder-node-colors",
          "blunder-winrate-thresholds",
          "board-image",
          "comment-background-color",
          "comment-font-color",
          "comment-font-size",
          "comment-node-color",
          "coordinate-bold",
          "coordinate-size",
          "font-name",
          "glass-accent-color",
          "glass-blur-radius",
          "glass-corner-radius",
          "glass-panel-border-color",
          "glass-panel-highlight-color",
          "glass-panel-overlay-color",
          "glass-panel-shadow-color",
          "liquid-blur-radius",
          "minimum-blunder-bar-width",
          "node-color-mode",
          "pure-background-color",
          "pure-board-color",
          "score-lead-stroke-width",
          "scorediff-in-variation-tree-factor",
          "scoremean-line-color",
          "shadow-size",
          "show-comment-node-color",
          "show-stone-shadow",
          "stone-indicator-type",
          "ui-font-name",
          "use-pure-background",
          "use-pure-board",
          "use-pure-stone",
          "use-scorediff-in-variation-tree",
          "var-panel-color",
          "white-stone-image",
          "winrate-font-name",
          "winrate-line-color",
          "winrate-miss-line-color",
          "winrate-stroke-width");

  private final Path directory;
  private final String id;
  private final String name;
  private final String author;
  private final String version;
  private final String compatibleAppVersion;
  private final String description;
  private final boolean hasManifest;
  private final List<String> errors;
  private final List<String> warnings;

  private ThemePack(
      Path directory,
      JSONObject manifest,
      boolean hasManifest,
      List<String> errors,
      List<String> warnings) {
    this.directory = directory;
    String folder = directory.getFileName() == null ? "" : directory.getFileName().toString();
    this.id = manifest.optString("id", folder).trim();
    this.name = manifest.optString("name", folder).trim();
    this.author = manifest.optString("author", "").trim();
    this.version = manifest.optString("version", "").trim();
    this.compatibleAppVersion = manifest.optString("compatibleAppVersion", "").trim();
    this.description = manifest.optString("description", "").trim();
    this.hasManifest = hasManifest;
    this.errors = Collections.unmodifiableList(errors);
    this.warnings = Collections.unmodifiableList(warnings);
  }

  /** Reads and validates the pack in {@code directory}; never throws for bad content. */
  public static ThemePack load(Path directory) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Path dir = directory.toAbsolutePath().normalize();
    JSONObject manifest = new JSONObject();
    boolean hasManifest = false;
    Path manifestFile = dir.resolve(MANIFEST);
    if (Files.isRegularFile(manifestFile, LinkOption.NOFOLLOW_LINKS)) {
      hasManifest = true;
      try {
        manifest = readJson(manifestFile);
        if (manifest.optInt("schemaVersion", -1) != 1) {
          errors.add(MANIFEST + ": unsupported schemaVersion");
        }
        if (manifest.optString("name", "").isBlank()) errors.add(MANIFEST + ": name is required");
      } catch (IOException | RuntimeException e) {
        errors.add(MANIFEST + ": " + e.getMessage());
        manifest = new JSONObject();
      }
    }
    Path themeFile = dir.resolve(THEME_FILE);
    if (!Files.isRegularFile(themeFile, LinkOption.NOFOLLOW_LINKS)) {
      errors.add(THEME_FILE + " is missing");
    } else {
      try {
        JSONObject theme = readJson(themeFile);
        for (String key : theme.keySet()) {
          if (!KNOWN_KEYS.contains(key)) warnings.add("unknown property " + key);
        }
        for (String key : IMAGE_KEYS) {
          String value = theme.optString(key, "");
          if (!value.isEmpty()) checkImage(dir, key, value, errors);
        }
      } catch (IOException | RuntimeException e) {
        errors.add(THEME_FILE + ": " + e.getMessage());
      }
    }
    return new ThemePack(dir, manifest, hasManifest, errors, warnings);
  }

  /**
   * Resolves an image named by a pack, or returns null when the name would leave the pack folder,
   * is not a supported image type, or is a link.
   */
  public static Path resolveImage(Path packDirectory, String value) {
    if (value == null || value.isBlank()) return null;
    String v = value.trim().replace('\\', '/');
    if (v.startsWith("/") || v.contains(":") || v.contains("..")) return null;
    String ext = v.substring(v.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    if (v.lastIndexOf('.') < 0 || !IMAGE_EXTENSIONS.contains(ext)) return null;
    Path dir = packDirectory.toAbsolutePath().normalize();
    Path resolved = dir.resolve(v).normalize();
    if (!resolved.startsWith(dir) || Files.isSymbolicLink(resolved)) return null;
    return resolved;
  }

  private static void checkImage(Path dir, String key, String value, List<String> errors) {
    Path p = resolveImage(dir, value);
    if (p == null) {
      errors.add(key + ": '" + value + "' must be a png/jpg/gif file inside the pack");
      return;
    }
    try {
      if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
        errors.add(key + ": '" + value + "' not found");
      } else if (Files.size(p) > MAX_IMAGE_BYTES) {
        errors.add(key + ": '" + value + "' is larger than 16 MB");
      }
    } catch (IOException e) {
      errors.add(key + ": " + e.getMessage());
    }
  }

  private static JSONObject readJson(Path file) throws IOException {
    if (Files.size(file) > MAX_TEXT_BYTES) throw new IOException("file is too large");
    return new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
  }

  /**
   * True when {@code appVersion} satisfies {@code compatibleAppVersion} ("", "2.5.3", ">=2.5",
   * "<3"). Packs without a requirement are compatible.
   */
  public boolean isCompatibleWith(String appVersion) {
    String req = compatibleAppVersion.replace(" ", "");
    if (req.isEmpty()) return true;
    String op =
        req.startsWith(">=") || req.startsWith("<=")
            ? req.substring(0, 2)
            : req.startsWith(">") || req.startsWith("<") || req.startsWith("=")
                ? req.substring(0, 1)
                : "=";
    String target = req.substring(op.equals("=") && !req.startsWith("=") ? 0 : op.length());
    int c = compareVersions(appVersion, target);
    switch (op) {
      case ">=":
        return c >= 0;
      case "<=":
        return c <= 0;
      case ">":
        return c > 0;
      case "<":
        return c < 0;
      default:
        return c == 0;
    }
  }

  static int compareVersions(String a, String b) {
    String[] x = a.split("[.\\-]");
    String[] y = b.split("[.\\-]");
    for (int i = 0; i < Math.max(x.length, y.length); i++) {
      int p = i < x.length ? leadingInt(x[i]) : 0;
      int q = i < y.length ? leadingInt(y[i]) : 0;
      if (p != q) return Integer.compare(p, q);
    }
    return 0;
  }

  private static int leadingInt(String s) {
    int n = 0;
    for (int i = 0; i < s.length() && Character.isDigit(s.charAt(i)) && i < 9; i++) {
      n = n * 10 + (s.charAt(i) - '0');
    }
    return n;
  }

  public Path directory() {
    return directory;
  }

  public String id() {
    return id;
  }

  public String name() {
    return name;
  }

  public String author() {
    return author;
  }

  public String version() {
    return version;
  }

  public String compatibleAppVersion() {
    return compatibleAppVersion;
  }

  public String description() {
    return description;
  }

  public boolean hasManifest() {
    return hasManifest;
  }

  /** Problems that make the pack unusable (missing files, paths outside the pack, ...). */
  public List<String> errors() {
    return errors;
  }

  /** Harmless issues, such as properties this version does not know. */
  public List<String> warnings() {
    return warnings;
  }

  public boolean isValid() {
    return errors.isEmpty();
  }

  /** Lists the packs (sub-folders with a theme.txt or pack.json) under each root, in order. */
  public static List<ThemePack> scan(Path... roots) {
    List<ThemePack> packs = new ArrayList<>();
    for (Path root : roots) {
      if (root == null || !Files.isDirectory(root)) continue;
      try (var stream = Files.list(root)) {
        stream
            .filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
            .filter(p -> Files.exists(p.resolve(THEME_FILE)) || Files.exists(p.resolve(MANIFEST)))
            .sorted()
            .forEach(p -> packs.add(load(p)));
      } catch (IOException e) {
        // an unreadable root lists no packs
      }
    }
    return packs;
  }
}
