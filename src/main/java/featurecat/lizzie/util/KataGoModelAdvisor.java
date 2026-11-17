package featurecat.lizzie.util;

import featurecat.lizzie.util.KataGoAutoSetupHelper.RemoteWeightInfo;
import featurecat.lizzie.util.KataGoAutoSetupHelper.TransformerTier;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Chooses the "best safe" KataGo network for the one-click model update.
 *
 * <p>Safe means: an HTTPS download from an official KataGo host, a published SHA-256 and size (so
 * the existing downloader can verify it), a minimum KataGo version the installed engine meets, and
 * enough free disk space. Best means: highest online Elo when known, otherwise the official
 * strongest network, then the catalogue tier (strongest, balanced, lightweight), then the newest.
 *
 * <p>Pure logic, no I/O: the caller supplies the candidate rows, the engine version and the disk
 * space. Speed is decided afterwards by a real benchmark of the downloaded file.
 */
public final class KataGoModelAdvisor {
  /** Below this many visits per second, analysis feels slow; a lighter network is suggested. */
  public static final double MIN_COMFORTABLE_VISITS_PER_SECOND = 80.0;

  /** Space kept free after a download, on top of the network itself. */
  static final long DISK_RESERVE_BYTES = 200L * 1024 * 1024;

  private static final Set<String> OFFICIAL_HOSTS =
      Set.of(
          "media.katagotraining.org",
          "katagotraining.org",
          "github.com",
          "objects.githubusercontent.com",
          "release-assets.githubusercontent.com");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  /** Outcome: candidates ordered best first, plus why other rows were left out. */
  public record Advice(List<RemoteWeightInfo> candidates, List<String> rejected) {
    public boolean hasCandidate() {
      return !candidates.isEmpty();
    }

    public RemoteWeightInfo best() {
      return candidates.isEmpty() ? null : candidates.get(0);
    }

    /** The next lighter candidate after {@code current}, or null. */
    public RemoteWeightInfo lighterThan(RemoteWeightInfo current) {
      int i = candidates.indexOf(current);
      for (int j = i + 1; j < candidates.size(); j++) {
        if (candidates.get(j).sizeBytes > 0 && candidates.get(j).sizeBytes < current.sizeBytes) {
          return candidates.get(j);
        }
      }
      return null;
    }
  }

  private KataGoModelAdvisor() {}

  /**
   * @param engineVersion the installed KataGo version, or "" when unknown (then the version the
   *     application bundles is assumed)
   * @param currentModelFile file name of the active network, or "" (it is never recommended)
   * @param usableDiskBytes free space in the weights folder, or a negative value when unknown
   */
  public static Advice advise(
      List<RemoteWeightInfo> rows,
      String engineVersion,
      String bundledEngineVersion,
      String currentModelFile,
      long usableDiskBytes) {
    String version =
        engineVersion == null || engineVersion.isBlank() ? bundledEngineVersion : engineVersion;
    List<RemoteWeightInfo> safe = new ArrayList<>();
    List<String> rejected = new ArrayList<>();
    for (RemoteWeightInfo r : rows) {
      String reason = rejection(r, version, usableDiskBytes);
      if (reason != null) {
        rejected.add(r.fileName() + ": " + reason);
      } else if (!r.fileName().equalsIgnoreCase(currentModelFile == null ? "" : currentModelFile)) {
        if (safe.stream().noneMatch(s -> s.fileName().equals(r.fileName()))) safe.add(r);
      }
    }
    safe.sort(BEST_FIRST);
    return new Advice(List.copyOf(safe), List.copyOf(rejected));
  }

  /** True when {@code current} already ranks at least as high as every safe candidate. */
  public static boolean currentIsBest(List<RemoteWeightInfo> rows, String currentModelFile) {
    RemoteWeightInfo current = null;
    for (RemoteWeightInfo r : rows) {
      if (r.fileName().equalsIgnoreCase(currentModelFile)) current = r;
    }
    if (current == null) return false;
    for (RemoteWeightInfo r : rows) {
      if (r != current && BEST_FIRST.compare(r, current) < 0) return false;
    }
    return true;
  }

  static String rejection(RemoteWeightInfo r, String engineVersion, long usableDiskBytes) {
    URI uri;
    try {
      uri = URI.create(r.downloadUrl == null ? "" : r.downloadUrl.trim());
    } catch (IllegalArgumentException e) {
      return "invalid download address";
    }
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    if (!"https".equalsIgnoreCase(uri.getScheme()) || !OFFICIAL_HOSTS.contains(host)) {
      return "not an HTTPS download from an official KataGo source";
    }
    if (!SHA256.matcher(r.sha256).matches() || r.sizeBytes <= 0) {
      return "no published SHA-256 and size to verify the download";
    }
    if (!r.minimumKataGoVersion.isEmpty()
        && !engineVersion.isBlank()
        && compareVersions(engineVersion, r.minimumKataGoVersion) < 0) {
      return "needs KataGo " + r.minimumKataGoVersion + " (installed " + engineVersion + ")";
    }
    if (usableDiskBytes >= 0 && usableDiskBytes < r.sizeBytes + DISK_RESERVE_BYTES) {
      return "not enough free disk space";
    }
    return null;
  }

  static final Comparator<RemoteWeightInfo> BEST_FIRST =
      Comparator.comparingDouble((RemoteWeightInfo r) -> -elo(r))
          .thenComparing(r -> !r.officialStrongest)
          .thenComparingInt(r -> -tierRank(r.transformerTier))
          .thenComparing(
              (RemoteWeightInfo r) -> r.uploadedAt == null ? "" : r.uploadedAt,
              Comparator.reverseOrder())
          .thenComparing(RemoteWeightInfo::fileName);

  /** Online Elo, or negative infinity when the row has none (ranked after rows with one). */
  static double elo(RemoteWeightInfo r) {
    if (r.eloRating == null) return Double.NEGATIVE_INFINITY;
    String digits = r.eloRating.trim().replaceAll("[^0-9.\\-]", " ").trim().split("\\s+")[0];
    try {
      return digits.isEmpty() ? Double.NEGATIVE_INFINITY : Double.parseDouble(digits);
    } catch (NumberFormatException e) {
      return Double.NEGATIVE_INFINITY;
    }
  }

  private static int tierRank(TransformerTier tier) {
    switch (tier) {
      case STRONGEST:
        return 4;
      case UNTIERED:
        return 3;
      case BALANCED:
        return 2;
      case LIGHTWEIGHT:
        return 1;
      default:
        return 0;
    }
  }

  static int compareVersions(String a, String b) {
    String[] x = a.trim().replaceFirst("^[vV]", "").split("[.\\-]");
    String[] y = b.trim().replaceFirst("^[vV]", "").split("[.\\-]");
    for (int i = 0; i < Math.max(x.length, y.length); i++) {
      int p = i < x.length ? leadingInt(x[i]) : 0;
      int q = i < y.length ? leadingInt(y[i]) : 0;
      if (p != q) return Integer.compare(p, q);
    }
    return 0;
  }

  private static int leadingInt(String s) {
    int n = 0;
    for (int i = 0; i < s.length() && i < 9 && Character.isDigit(s.charAt(i)); i++) {
      n = n * 10 + (s.charAt(i) - '0');
    }
    return n;
  }
}
