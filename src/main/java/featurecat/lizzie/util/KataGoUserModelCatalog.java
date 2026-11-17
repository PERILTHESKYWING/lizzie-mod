package featurecat.lizzie.util;

import featurecat.lizzie.util.KataGoAutoSetupHelper.RemoteWeightInfo;
import featurecat.lizzie.util.KataGoAutoSetupHelper.TransformerTier;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extra KataGo networks listed in {@code katago-models.json} in the work directory.
 *
 * <p>The built-in catalogue ({@code katago-assets.json}) and the katagotraining.org refresh stay
 * the primary sources. This file lets a user (or a future maintainer) pin newer official networks,
 * with their SHA-256 and size, without changing code. Entries are only accepted when they would be
 * as safe to download as the built-in rows:
 *
 * <ul>
 *   <li>HTTPS URL on an official KataGo host (katagotraining.org or GitHub releases);
 *   <li>a 64-hex-digit SHA-256 and a positive size, checked after download by the existing
 *       verifier;
 *   <li>a plain file name (no directories), so downloads always land in {@code weights/}.
 * </ul>
 *
 * Rejected entries are reported with a reason and never shown as downloadable. Rows already in the
 * built-in catalogue keep their built-in metadata.
 */
public final class KataGoUserModelCatalog {
  private static final Logger LOGGER = LoggerFactory.getLogger(KataGoUserModelCatalog.class);
  public static final String FILE_NAME = "katago-models.json";
  static final int SCHEMA = 1;
  private static final Set<String> TRUSTED_HOSTS =
      Set.of(
          "media.katagotraining.org",
          "katagotraining.org",
          "github.com",
          "objects.githubusercontent.com",
          "release-assets.githubusercontent.com");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern FILE =
      Pattern.compile("[A-Za-z0-9._-]{1,160}\\.(bin\\.gz|txt\\.gz|bin)");
  private static final Pattern VERSION = Pattern.compile("\\d+(\\.\\d+){0,3}");

  /** One accepted entry, with the descriptive fields the setup rows cannot hold. */
  public record Entry(
      String id,
      String displayName,
      String fileName,
      String downloadUrl,
      String sha256,
      long sizeBytes,
      String minimumKataGoVersion,
      String architecture,
      String publishedAt,
      List<String> backends) {}

  /** Parse result: accepted entries and human-readable reasons for rejected ones. */
  public record Result(List<Entry> entries, List<String> rejected) {}

  private KataGoUserModelCatalog() {}

  /** Reads {@code workDir/katago-models.json}; a missing file is an empty catalogue. */
  public static Result load(Path workDir) {
    if (workDir == null) return new Result(List.of(), List.of());
    Path file = workDir.resolve(FILE_NAME);
    if (!Files.isRegularFile(file)) return new Result(List.of(), List.of());
    try {
      if (Files.size(file) > 1_000_000) {
        return new Result(List.of(), List.of(FILE_NAME + ": file is larger than 1 MB"));
      }
      return parse(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException | RuntimeException e) {
      LOGGER.warn("Ignoring unreadable {}", file, e);
      return new Result(List.of(), List.of(FILE_NAME + ": " + e.getMessage()));
    }
  }

  static Result parse(String json) {
    JSONObject root = new JSONObject(json);
    int schema = root.optInt("schemaVersion", -1);
    if (schema != SCHEMA) {
      return new Result(List.of(), List.of("unsupported schemaVersion " + schema));
    }
    List<Entry> entries = new ArrayList<>();
    List<String> rejected = new ArrayList<>();
    Set<String> files = new HashSet<>();
    JSONArray models = root.optJSONArray("models");
    if (models == null) return new Result(List.of(), List.of());
    for (int i = 0; i < models.length(); i++) {
      JSONObject m = models.optJSONObject(i);
      String label = m == null ? "#" + (i + 1) : m.optString("id", "#" + (i + 1));
      try {
        if (m == null) throw new IllegalArgumentException("not an object");
        Entry e = validate(m);
        if (!files.add(e.fileName())) throw new IllegalArgumentException("duplicate file name");
        entries.add(e);
      } catch (IllegalArgumentException ex) {
        rejected.add(label + ": " + ex.getMessage());
      }
    }
    for (String r : rejected) LOGGER.warn("katago-models.json entry rejected: {}", r);
    return new Result(List.copyOf(entries), List.copyOf(rejected));
  }

  private static Entry validate(JSONObject m) {
    String url = m.optString("downloadUrl", "").trim();
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("invalid downloadUrl");
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      throw new IllegalArgumentException("downloadUrl must use HTTPS");
    }
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    if (!TRUSTED_HOSTS.contains(host) || uri.getUserInfo() != null || uri.getPort() != -1) {
      throw new IllegalArgumentException("downloadUrl host is not an official KataGo source");
    }
    String path = uri.getPath() == null ? "" : uri.getPath();
    String urlFile = path.substring(path.lastIndexOf('/') + 1);
    String fileName = m.optString("fileName", urlFile).trim();
    if (!FILE.matcher(fileName).matches() || fileName.contains("..")) {
      throw new IllegalArgumentException("unsafe or unsupported fileName");
    }
    if (!fileName.equals(urlFile)) {
      throw new IllegalArgumentException("fileName must match the last part of downloadUrl");
    }
    String sha = m.optString("sha256", "").trim().toLowerCase(Locale.ROOT);
    if (!SHA256.matcher(sha).matches()) throw new IllegalArgumentException("sha256 is required");
    long size = m.optLong("sizeBytes", 0L);
    if (size <= 0) throw new IllegalArgumentException("sizeBytes is required");
    String minVersion = m.optString("minimumKataGoVersion", "").trim();
    if (!minVersion.isEmpty() && !VERSION.matcher(minVersion).matches()) {
      throw new IllegalArgumentException("invalid minimumKataGoVersion");
    }
    List<String> backends = new ArrayList<>();
    JSONArray b = m.optJSONArray("backends");
    if (b != null) for (int i = 0; i < b.length(); i++) backends.add(b.optString(i));
    String id = m.optString("id", fileName).trim();
    return new Entry(
        id,
        m.optString("displayName", id).trim(),
        fileName,
        url,
        sha,
        size,
        minVersion,
        m.optString("architecture", "").trim(),
        m.optString("publishedAt", "").trim(),
        List.copyOf(backends));
  }

  /**
   * Appends accepted user entries to the official rows. Rows whose file is already listed keep the
   * built-in metadata. Never throws: a broken user file must not break the official list.
   */
  static List<RemoteWeightInfo> merge(List<RemoteWeightInfo> official, Path workDir) {
    try {
      Result result = load(workDir);
      if (result.entries().isEmpty()) return official;
      List<RemoteWeightInfo> merged = new ArrayList<>(official);
      Set<String> listed = new HashSet<>();
      for (RemoteWeightInfo r : official) listed.add(r.fileName());
      for (Entry e : result.entries()) {
        if (listed.contains(e.fileName())) continue;
        String type = e.displayName().isEmpty() ? e.id() : e.displayName();
        if (!e.architecture().isEmpty()) type += " · " + e.architecture();
        merged.add(
            new RemoteWeightInfo(
                type,
                e.fileName(),
                e.downloadUrl(),
                e.publishedAt(),
                "",
                false,
                false,
                e.sha256(),
                e.sizeBytes(),
                e.minimumKataGoVersion(),
                TransformerTier.NONE));
      }
      return merged;
    } catch (RuntimeException e) {
      LOGGER.warn("Ignoring katago-models.json", e);
      return official;
    }
  }
}
