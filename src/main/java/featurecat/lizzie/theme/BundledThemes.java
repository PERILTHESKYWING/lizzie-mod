package featurecat.lizzie.theme;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies the themes that ship inside the jar (Hon-kaya, Bamboo, Shin-kaya) into the theme folder,
 * so they appear in Settings → Theme. A theme is (re)written only when its folder is missing or was
 * written by an older bundle; a folder the user made with the same name is left alone.
 */
public final class BundledThemes {
  private static final Logger LOGGER = LoggerFactory.getLogger(BundledThemes.class);
  static final String INDEX = "/themes/bundled.json";
  static final String MARKER = ".bundled-version";

  private BundledThemes() {}

  /** Installs into {@code themeRoot}; returns the names written. Never throws. */
  public static List<String> install(Path themeRoot) {
    List<String> written = new ArrayList<>();
    try (InputStream in = BundledThemes.class.getResourceAsStream(INDEX)) {
      if (in == null) return written;
      JSONObject index = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      String version = String.valueOf(index.optInt("version", 1));
      JSONObject themes = index.getJSONObject("themes");
      for (String name : themes.keySet()) {
        if (installTheme(themeRoot.resolve(name), name, themes.getJSONArray(name), version)) {
          written.add(name);
        }
      }
    } catch (IOException | RuntimeException e) {
      LOGGER.warn("Could not install the bundled themes into {}", themeRoot, e);
    }
    return written;
  }

  private static boolean installTheme(Path dir, String name, JSONArray files, String version)
      throws IOException {
    Path marker = dir.resolve(MARKER);
    if (Files.isDirectory(dir)) {
      if (!Files.isRegularFile(marker)) return false; // the user's own theme of that name
      if (version.equals(Files.readString(marker, StandardCharsets.UTF_8).trim())) return false;
    }
    Files.createDirectories(dir);
    for (int i = 0; i < files.length(); i++) {
      String file = files.getString(i);
      try (InputStream in =
          BundledThemes.class.getResourceAsStream("/themes/" + name + "/" + file)) {
        if (in == null) throw new IOException("missing bundled file " + name + "/" + file);
        Files.copy(in, dir.resolve(file), StandardCopyOption.REPLACE_EXISTING);
      }
    }
    Files.writeString(marker, version, StandardCharsets.UTF_8);
    return true;
  }
}
