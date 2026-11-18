package featurecat.lizzie.explorer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opening Explorer preferences, kept in the explorer's own folder ({@code explorer-settings.json})
 * so the application's config file format is untouched. Unknown or damaged files fall back to the
 * defaults.
 */
public final class OpeningExplorerSettings {
  private static final Logger LOGGER = LoggerFactory.getLogger(OpeningExplorerSettings.class);
  static final String FILE = "explorer-settings.json";

  public boolean visible;
  public boolean includeVariations;
  public boolean indexVariations;
  public boolean showOnBoard = true;
  public boolean showRawCounts;
  public Set<Integer> collections = new LinkedHashSet<>();
  public String player = "";
  public String rank = "";
  public String event = "";
  public int minYear;
  public int maxYear;
  public String lastFolder = "";

  /** Colours as #RRGGBB: board markers and bars, the selected move, the top bar. */
  public String markColor = DEFAULT_MARK;

  public String selectedColor = DEFAULT_SELECTED;
  public String barColor = DEFAULT_BAR;
  public String lastFoxAccounts = "";

  public static final String DEFAULT_MARK = "#3478DC";
  public static final String DEFAULT_SELECTED = "#EB781E";
  public static final String DEFAULT_BAR = "#2F6FD6";

  /** Parses #RRGGBB, falling back to {@code fallback} for anything else. */
  public static java.awt.Color color(String hex, String fallback) {
    String v = hex == null ? "" : hex.trim();
    if (!v.matches("#[0-9A-Fa-f]{6}")) v = fallback;
    return new java.awt.Color(Integer.parseInt(v.substring(1), 16));
  }

  public static String hex(java.awt.Color c) {
    return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
  }

  public OpeningFilter filter() {
    return new OpeningFilter(collections, player, rank, event, minYear, maxYear);
  }

  public static OpeningExplorerSettings load(Path dir) {
    OpeningExplorerSettings s = new OpeningExplorerSettings();
    Path file = dir.resolve(FILE);
    if (!Files.exists(file)) return s;
    try {
      JSONObject o = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
      s.visible = o.optBoolean("visible", false);
      s.includeVariations = o.optBoolean("includeVariations", false);
      s.indexVariations = o.optBoolean("indexVariations", false);
      s.showOnBoard = o.optBoolean("showOnBoard", true);
      s.showRawCounts = o.optBoolean("showRawCounts", false);
      JSONArray a = o.optJSONArray("collections");
      if (a != null) for (int i = 0; i < a.length(); i++) s.collections.add(a.optInt(i));
      s.player = o.optString("player", "");
      s.rank = o.optString("rank", "");
      s.event = o.optString("event", "");
      s.minYear = o.optInt("minYear", 0);
      s.maxYear = o.optInt("maxYear", 0);
      s.lastFolder = o.optString("lastFolder", "");
      s.markColor = o.optString("markColor", DEFAULT_MARK);
      s.selectedColor = o.optString("selectedColor", DEFAULT_SELECTED);
      s.barColor = o.optString("barColor", DEFAULT_BAR);
      s.lastFoxAccounts = o.optString("lastFoxAccounts", "");
    } catch (IOException | RuntimeException e) {
      LOGGER.warn("Ignoring unreadable opening explorer settings {}", file, e);
      return new OpeningExplorerSettings();
    }
    return s;
  }

  public void save(Path dir) {
    JSONObject o = new JSONObject();
    o.put("visible", visible);
    o.put("includeVariations", includeVariations);
    o.put("indexVariations", indexVariations);
    o.put("showOnBoard", showOnBoard);
    o.put("showRawCounts", showRawCounts);
    o.put("collections", new JSONArray(collections));
    o.put("player", player);
    o.put("rank", rank);
    o.put("event", event);
    o.put("minYear", minYear);
    o.put("maxYear", maxYear);
    o.put("lastFolder", lastFolder);
    o.put("markColor", markColor);
    o.put("selectedColor", selectedColor);
    o.put("barColor", barColor);
    o.put("lastFoxAccounts", lastFoxAccounts);
    try {
      Files.createDirectories(dir);
      Path tmp = dir.resolve(FILE + ".tmp");
      Files.writeString(tmp, o.toString(2), StandardCharsets.UTF_8);
      Files.move(tmp, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      LOGGER.warn("Could not save opening explorer settings", e);
    }
  }
}
