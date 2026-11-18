package featurecat.lizzie.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * The fork's self-update description: {@code mod-update.json} on the repository's {@code
 * mod-latest} release, written by the publish-mod-jar workflow, and {@code mod-build.properties}
 * inside the jar, which says which commit and repository this build came from.
 *
 * <p>Trust model: the manifest and the jar are fetched over HTTPS from the GitHub release of the
 * one repository named at build time; the SHA-256 and size in the manifest are checked before the
 * jar replaces anything. That protects against corrupt or truncated downloads, not against someone
 * who controls that repository.
 */
public final class ModUpdateManifest {
  public static final String RELEASE_TAG = "mod-latest";
  public static final String JAR_NAME = "lizzie-yzy2.5.3-shaded.jar";
  static final String DEFAULT_REPOSITORY = "PERILTHESKYWING/lizzie-mod";
  private static final Pattern REPO =
      Pattern.compile("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{7,40}");

  /** What a published build offers. */
  public record Offer(String commit, String builtAt, String jarSha256, long jarSize) {
    public String shortCommit() {
      return commit.substring(0, Math.min(7, commit.length()));
    }
  }

  /** Where this running build came from. */
  public record Build(String repository, String commit) {
    public boolean isPublished() {
      return COMMIT.matcher(commit).matches();
    }
  }

  private ModUpdateManifest() {}

  /** Reads {@code /mod-build.properties}; local builds report commit "dev". */
  public static Build currentBuild() {
    Properties p = new Properties();
    try (InputStream in = ModUpdateManifest.class.getResourceAsStream("/mod-build.properties")) {
      if (in != null) p.load(in);
    } catch (IOException ignored) {
      // treated as a development build
    }
    return build(p.getProperty("repository", ""), p.getProperty("commit", ""));
  }

  static Build build(String repository, String commit) {
    String repo = repository == null ? "" : repository.trim();
    String c = commit == null ? "" : commit.trim().toLowerCase(Locale.ROOT);
    return new Build(
        REPO.matcher(repo).matches() ? repo : DEFAULT_REPOSITORY,
        COMMIT.matcher(c).matches() ? c : "dev");
  }

  public static String manifestUrl(Build build) {
    return "https://github.com/"
        + build.repository()
        + "/releases/download/"
        + RELEASE_TAG
        + "/mod-update.json";
  }

  public static String jarUrl(Build build) {
    return "https://github.com/"
        + build.repository()
        + "/releases/download/"
        + RELEASE_TAG
        + "/"
        + JAR_NAME;
  }

  /** Parses and validates the manifest; throws for anything that is not a usable offer. */
  public static Offer parse(String json) throws IOException {
    try {
      JSONObject o = new JSONObject(json);
      if (o.optInt("schema", -1) != 1) throw new IOException("unsupported update manifest");
      String commit = o.optString("commit", "").trim().toLowerCase(Locale.ROOT);
      String sha = o.optString("sha256", "").trim().toLowerCase(Locale.ROOT);
      long size = o.optLong("size", -1);
      if (!COMMIT.matcher(commit).matches()) throw new IOException("update manifest: bad commit");
      if (!SHA256.matcher(sha).matches()) throw new IOException("update manifest: bad SHA-256");
      if (size < 1_000_000 || size > 500_000_000L) {
        throw new IOException("update manifest: implausible size " + size);
      }
      if (!JAR_NAME.equals(o.optString("jar", JAR_NAME))) {
        throw new IOException("update manifest: unexpected file name");
      }
      return new Offer(commit, o.optString("builtAt", "").trim(), sha, size);
    } catch (RuntimeException e) {
      throw new IOException("unreadable update manifest: " + e.getMessage(), e);
    }
  }

  /** True when the offer is a different build from the one running. */
  public static boolean isNewer(Build running, Offer offer) {
    if (!running.isPublished()) return true;
    String a = running.commit();
    String b = offer.commit();
    return !(a.startsWith(b) || b.startsWith(a));
  }
}
