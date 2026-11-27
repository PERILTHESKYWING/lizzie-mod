package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.SourceDataLine;
import org.junit.jupiter.api.Test;

class SoundMixerTest {
  private static final long MS = 1_000_000L;

  @Test
  void aBurstOfRequestsPlaysOnceForTheLastMove() {
    SoundMixer mixer = new SoundMixer(f -> null, w -> null, w -> {});
    for (int i = 0; i < 100; i++) mixer.requestAt("stone", "move" + i, i * 100_000L);
    mixer.requestAt("countdown", "5.wav", 0);

    assertEquals("5.wav", mixer.takeSettledForTest(9 * MS), "other kinds are not swallowed");
    assertNull(mixer.takeSettledForTest(9 * MS), "the burst has not settled yet");
    assertEquals("move99", mixer.takeSettledForTest(20 * MS));
    assertNull(mixer.takeSettledForTest(30 * MS));
  }

  @Test
  void movesAtKeyRepeatSpeedEachPlay() {
    SoundMixer mixer = new SoundMixer(f -> null, w -> null, w -> {});
    mixer.requestAt("stone", "a", 0);
    assertEquals("a", mixer.takeSettledForTest(SoundMixer.SETTLE_NANOS));
    mixer.requestAt("stone", "b", 33 * MS);
    assertEquals("b", mixer.takeSettledForTest(33 * MS + SoundMixer.SETTLE_NANOS));
  }

  @Test
  void overlappingSoundsAreAddedAndClipped() {
    List<SoundMixer.Voice> voices = new ArrayList<>();
    voices.add(new SoundMixer.Voice(new short[] {1000, 2000, 30000, 4}, 1));
    voices.add(new SoundMixer.Voice(new short[] {10, -20, 30000}, 1));
    short[] out = new short[4];

    SoundMixer.mix(voices, out);

    assertArrayEquals(new short[] {1010, 1980, Short.MAX_VALUE, 4}, out);
    assertTrue(voices.isEmpty(), "finished sounds are dropped");
  }

  @Test
  void aReplacedSoundFadesOutInsteadOfClicking() {
    short[] loud = new short[100];
    java.util.Arrays.fill(loud, (short) 10000);
    SoundMixer.Voice voice = new SoundMixer.Voice(loud, 1);
    voice.fadeOut(4);
    List<SoundMixer.Voice> voices = new ArrayList<>(List.of(voice));
    short[] out = new short[8];

    SoundMixer.mix(voices, out);

    assertArrayEquals(new short[] {10000, 7500, 5000, 2500, 0, 0, 0, 0}, out);
    assertTrue(voices.isEmpty());
  }

  @Test
  void soundsShareOneOpenLineAndArriveIntact() throws Exception {
    AudioFormat format = new AudioFormat(22050, 16, 1, true, false);
    short[] clack = new short[2205];
    for (int i = 0; i < clack.length; i++) clack[i] = (short) (1000 + i);
    AtomicInteger opened = new AtomicInteger();
    ByteArrayOutputStream written = new ByteArrayOutputStream();
    CountDownLatch twoSounds = new CountDownLatch(2);
    SoundMixer mixer =
        new SoundMixer(
            f -> {
              opened.incrementAndGet();
              return fakeLine(written, twoSounds);
            },
            w -> new SoundMixer.Pcm(format, clack),
            w -> {
              throw new AssertionError("no fallback for a supported sound");
            });

    mixer.play("stone", "/sound/Stone.wav");
    awaitStops(twoSounds, 1);
    mixer.play("stone", "/sound/Stone.wav");
    assertTrue(twoSounds.await(10, TimeUnit.SECONDS));

    assertEquals(1, opened.get(), "the line is opened once, not per move");
    short[] audio = shorts(written.toByteArray());
    int start = firstNonZero(audio, 0);
    assertEquals(
        Math.round(22050 * (SoundMixer.TARGET_MS - SoundMixer.CHUNK_MS) / 1000f),
        start,
        "every sound starts after the same fixed fill");
    for (int i = 0; i < clack.length; i++) assertEquals(clack[i], audio[start + i]);
  }

  @Test
  void bundledSoundsDecodeToSixteenBitPcm() throws Exception {
    for (String name : List.of("Stone.wav", "deadStone.wav", "deadStoneMore.wav", "3.wav")) {
      SoundMixer.Pcm pcm = SoundPlayer.decode("/sound/" + name);
      assertEquals(16, pcm.format().getSampleSizeInBits(), name);
      assertTrue(pcm.samples().length > 2000, name);
    }
  }

  private static void awaitStops(CountDownLatch latch, long remaining) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (latch.getCount() > remaining && System.nanoTime() < deadline) Thread.onSpinWait();
  }

  private static SourceDataLine fakeLine(ByteArrayOutputStream written, CountDownLatch stops) {
    boolean[] running = {false};
    return (SourceDataLine)
        Proxy.newProxyInstance(
            SoundMixerTest.class.getClassLoader(),
            new Class<?>[] {SourceDataLine.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "write" -> {
                    synchronized (written) {
                      written.write((byte[]) args[0], (int) args[1], (int) args[2]);
                    }
                    yield (int) args[2];
                  }
                  case "getBufferSize", "available" -> 1 << 16; // plays instantly
                  case "isRunning" -> running[0];
                  case "start" -> {
                    running[0] = true;
                    yield null;
                  }
                  case "stop" -> {
                    running[0] = false;
                    stops.countDown();
                    yield null;
                  }
                  default -> null;
                });
  }

  private static short[] shorts(byte[] bytes) {
    short[] out = new short[bytes.length / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (short) ((bytes[2 * i] & 0xff) | (bytes[2 * i + 1] << 8));
    }
    return out;
  }

  private static int firstNonZero(short[] audio, int from) {
    for (int i = from; i < audio.length; i++) if (audio[i] != 0) return i;
    return -1;
  }
}
