package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.util.KataGoAutoSetupHelper.RemoteWeightInfo;
import featurecat.lizzie.util.KataGoAutoSetupHelper.TransformerTier;
import java.util.List;
import org.junit.jupiter.api.Test;

class KataGoModelAdvisorTest {
  private static final String BASE =
      "https://media.katagotraining.org/uploaded/networks/models/kata1/";

  private static RemoteWeightInfo row(
      String file, String elo, long size, String minVersion, TransformerTier tier) {
    return new RemoteWeightInfo(
        "test",
        file,
        BASE + file,
        "2026-01-01",
        elo,
        false,
        false,
        "a".repeat(64),
        size,
        minVersion,
        tier);
  }

  private static final RemoteWeightInfo STRONG =
      row("strong.bin.gz", "14000", 260_000_000L, "1.17.0", TransformerTier.STRONGEST);
  private static final RemoteWeightInfo BALANCED =
      row("balanced.bin.gz", "13500", 94_000_000L, "1.17.0", TransformerTier.BALANCED);
  private static final RemoteWeightInfo LIGHT =
      row("light.bin.gz", "13000", 38_000_000L, "1.17.0", TransformerTier.LIGHTWEIGHT);

  @Test
  void picksTheStrongestSafeNetworkAndOffersALighterOne() {
    KataGoModelAdvisor.Advice a =
        KataGoModelAdvisor.advise(List.of(LIGHT, STRONG, BALANCED), "1.18.2", "1.18.2", "", -1);
    assertEquals(STRONG, a.best());
    assertEquals(BALANCED, a.lighterThan(STRONG));
    assertEquals(LIGHT, a.lighterThan(BALANCED));
    assertNull(a.lighterThan(LIGHT));
  }

  @Test
  void neverRecommendsTheCurrentNetworkAndKnowsWhenItIsBest() {
    List<RemoteWeightInfo> rows = List.of(STRONG, BALANCED);
    assertTrue(KataGoModelAdvisor.currentIsBest(rows, "strong.bin.gz"));
    assertFalse(KataGoModelAdvisor.currentIsBest(rows, "balanced.bin.gz"));
    assertEquals(
        BALANCED, KataGoModelAdvisor.advise(rows, "1.18.2", "1.18.2", "strong.bin.gz", -1).best());
    assertFalse(KataGoModelAdvisor.currentIsBest(rows, "custom.bin.gz"));
  }

  @Test
  void rejectsUnsafeIncompatibleOrTooLargeNetworks() {
    RemoteWeightInfo http =
        new RemoteWeightInfo(
            "t",
            "x.bin.gz",
            "http://media.katagotraining.org/x.bin.gz",
            "",
            "15000",
            false,
            false,
            "a".repeat(64),
            10,
            "",
            TransformerTier.NONE);
    RemoteWeightInfo foreign =
        new RemoteWeightInfo(
            "t",
            "y.bin.gz",
            "https://example.com/y.bin.gz",
            "",
            "15000",
            false,
            false,
            "a".repeat(64),
            10,
            "",
            TransformerTier.NONE);
    RemoteWeightInfo noDigest =
        new RemoteWeightInfo(
            "t",
            "z.bin.gz",
            BASE + "z.bin.gz",
            "",
            "15000",
            false,
            false,
            "",
            10,
            "",
            TransformerTier.NONE);
    RemoteWeightInfo future = row("future.bin.gz", "16000", 10, "9.0.0", TransformerTier.UNTIERED);
    KataGoModelAdvisor.Advice a =
        KataGoModelAdvisor.advise(
            List.of(http, foreign, noDigest, future, LIGHT), "1.18.2", "1.18.2", "", -1);
    assertEquals(List.of(LIGHT), a.candidates());
    assertEquals(4, a.rejected().size());
    long disk = 100_000_000L + KataGoModelAdvisor.DISK_RESERVE_BYTES;
    assertEquals(
        BALANCED,
        KataGoModelAdvisor.advise(List.of(STRONG, BALANCED), "1.18.2", "1.18.2", "", disk).best());
  }

  @Test
  void unknownEngineVersionFallsBackToTheBundledVersion() {
    assertEquals(
        0, KataGoModelAdvisor.advise(List.of(STRONG), "", "1.16.0", "", -1).candidates().size());
    assertEquals(STRONG, KataGoModelAdvisor.advise(List.of(STRONG), "", "1.18.2", "", -1).best());
    assertTrue(KataGoModelAdvisor.compareVersions("v1.18.2", "1.17.0") > 0);
  }

  @Test
  void rowsWithoutEloRankByTier() {
    RemoteWeightInfo a = row("a.bin.gz", "", 10, "", TransformerTier.BALANCED);
    RemoteWeightInfo b = row("b.bin.gz", "", 10, "", TransformerTier.STRONGEST);
    assertEquals(b, KataGoModelAdvisor.advise(List.of(a, b), "1.18.2", "1.18.2", "", -1).best());
    assertEquals(14000.0, KataGoModelAdvisor.elo(STRONG), 1e-9);
    assertEquals(13500.0, KataGoModelAdvisor.elo(row("c", "13500 ± 20", 1, "", null)), 1e-9);
  }
}
