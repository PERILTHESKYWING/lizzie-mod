package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.util.KataGoAutoSetupHelper.RemoteWeightInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KataGoUserModelCatalogTest {
  private static final String SHA = "a".repeat(64);
  private static final String URL =
      "https://media.katagotraining.org/uploaded/networks/models/kata1/kata1-new-b28.bin.gz";

  @TempDir Path tmp;

  private static JSONObject entry(String url, String sha, long size) {
    return new JSONObject()
        .put("id", "new")
        .put("displayName", "kata1 b28 (2027)")
        .put("downloadUrl", url)
        .put("sha256", sha)
        .put("sizeBytes", size)
        .put("minimumKataGoVersion", "1.16.0")
        .put("architecture", "b28c512nbt")
        .put("backends", new JSONArray(List.of("cuda", "opencl", "eigen")));
  }

  private static String catalog(JSONObject... entries) {
    return new JSONObject()
        .put("schemaVersion", 1)
        .put("models", new JSONArray(entries))
        .toString();
  }

  @Test
  void acceptsPinnedOfficialEntries() {
    KataGoUserModelCatalog.Result r = KataGoUserModelCatalog.parse(catalog(entry(URL, SHA, 10)));
    assertEquals(1, r.entries().size());
    assertTrue(r.rejected().isEmpty());
    KataGoUserModelCatalog.Entry e = r.entries().get(0);
    assertEquals("kata1-new-b28.bin.gz", e.fileName());
    assertEquals(List.of("cuda", "opencl", "eigen"), e.backends());
  }

  @Test
  void rejectsUnsafeOrUnverifiableEntries() {
    KataGoUserModelCatalog.Result r =
        KataGoUserModelCatalog.parse(
            catalog(
                entry(URL.replace("https", "http"), SHA, 10), // not HTTPS
                entry("https://evil.example.com/kata1-x.bin.gz", SHA, 10), // untrusted host
                entry(URL, "", 10), // no checksum
                entry(URL, SHA, 0), // no size
                entry(URL, SHA, 10).put("fileName", "../kata1-new-b28.bin.gz"), // path escape
                entry(
                    "https://github.com/x/y/releases/download/v1/run.exe", SHA, 10), // not a model
                entry(URL, SHA, 10).put("minimumKataGoVersion", "latest")));
    assertEquals(0, r.entries().size());
    assertEquals(7, r.rejected().size());
  }

  @Test
  void unsupportedSchemaIsIgnored() {
    KataGoUserModelCatalog.Result r =
        KataGoUserModelCatalog.parse(new JSONObject().put("schemaVersion", 99).toString());
    assertTrue(r.entries().isEmpty());
    assertEquals(1, r.rejected().size());
  }

  @Test
  void mergeAppendsNewModelsAndKeepsBuiltInRows() throws Exception {
    List<RemoteWeightInfo> official = KataGoAutoSetupHelper.officialTransformerWeights();
    assertSame(official, KataGoUserModelCatalog.merge(official, tmp)); // no file
    String builtInUrl =
        "https://media.katagotraining.org/uploaded/networks/models/kata1/"
            + official.get(0).fileName();
    Files.writeString(
        tmp.resolve(KataGoUserModelCatalog.FILE_NAME),
        catalog(entry(URL, SHA, 10), entry(builtInUrl, "b".repeat(64), 5)),
        StandardCharsets.UTF_8);
    List<RemoteWeightInfo> merged = KataGoUserModelCatalog.merge(official, tmp);
    assertEquals(official.size() + 1, merged.size());
    RemoteWeightInfo added = merged.get(merged.size() - 1);
    assertEquals("kata1-new-b28.bin.gz", added.fileName());
    assertEquals(SHA, added.sha256);
    assertEquals(10L, added.sizeBytes);
    assertEquals("1.16.0", added.minimumKataGoVersion);
    // The built-in row keeps its own pinned digest.
    assertEquals(official.get(0).sha256, merged.get(0).sha256);
  }

  @Test
  void brokenFileNeverBreaksTheOfficialList() throws Exception {
    List<RemoteWeightInfo> official = KataGoAutoSetupHelper.officialTransformerWeights();
    Files.writeString(
        tmp.resolve(KataGoUserModelCatalog.FILE_NAME), "{not json", StandardCharsets.UTF_8);
    assertEquals(official, KataGoUserModelCatalog.merge(official, tmp));
    assertEquals(1, KataGoUserModelCatalog.load(tmp).rejected().size());
  }
}
