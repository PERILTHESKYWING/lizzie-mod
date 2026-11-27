package featurecat.lizzie.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.SourceDataLine;

/**
 * Plays the short stone, capture and countdown sounds with a steady, low latency.
 *
 * <p>Each sound used to start a thread that opened a new audio line, which takes from a few to a
 * few hundred milliseconds depending on the system, so the click came at a different delay every
 * time; a shared counter (not thread safe) dropped sounds, and a jump through many moves started a
 * burst of threads. Here the sounds are decoded once, one line stays open, and a single audio
 * thread mixes up to {@link #MAX_VOICES} overlapping sounds, keeping the line's buffer at a fixed
 * fill so every sound is heard the same time after its move. Requests that come within {@link
 * #SETTLE_NANOS} of each other (a jump through many moves) play once, for the last one.
 */
final class SoundMixer {
  static final long SETTLE_NANOS = 8_000_000L;
  static final int MAX_VOICES = 4;
  static final int CHUNK_MS = 10;
  static final int TARGET_MS = 40;
  static final int FADE_MS = 4;
  static final long IDLE_CLOSE_NANOS = 5L * 60 * 1_000_000_000L;

  /** Decoded audio: signed 16-bit little-endian samples, interleaved by channel. */
  record Pcm(AudioFormat format, short[] samples) {}

  @FunctionalInterface
  interface Decoder {
    /** The decoded sound, or null when it cannot be decoded to 16-bit PCM (played the old way). */
    Pcm decode(String wav) throws Exception;
  }

  private static volatile SoundMixer shared;

  private final SoundPlayer.LineProvider lines;
  private final Decoder decoder;
  private final Consumer<String> fallback;

  private final Object lock = new Object();

  /** The newest request of each kind (stones, countdown), by kind; guarded by lock. */
  private final Map<String, Request> pending = new java.util.LinkedHashMap<>();

  private record Request(String wav, long at) {}

  private Thread thread; // guarded by lock

  // Audio thread only.
  private final Map<String, Pcm> cache = new HashMap<>();
  private final List<Voice> voices = new ArrayList<>();
  private SourceDataLine line;
  private AudioFormat lineFormat;
  private long idleSince;
  private short[] mixBuffer = new short[0];
  private byte[] byteBuffer = new byte[0];

  SoundMixer(SoundPlayer.LineProvider lines, Decoder decoder, Consumer<String> fallback) {
    this.lines = lines;
    this.decoder = decoder;
    this.fallback = fallback;
  }

  static SoundMixer shared() {
    SoundMixer m = shared;
    if (m == null) {
      synchronized (SoundMixer.class) {
        m = shared;
        if (m == null) {
          m =
              new SoundMixer(
                  SoundPlayer::defaultLine,
                  SoundPlayer::decode,
                  wav -> {
                    Thread t = new Thread(() -> SoundPlayer.play(wav), "sound-fallback");
                    t.setDaemon(true);
                    t.start();
                  });
          shared = m;
        }
      }
    }
    return m;
  }

  /**
   * Asks for {@code wav} to be played; returns at once. A newer request of the same {@code kind}
   * within {@link #SETTLE_NANOS} replaces it, so a burst plays once; other kinds are kept.
   */
  void play(String kind, String wav) {
    synchronized (lock) {
      pending.put(kind, new Request(wav, System.nanoTime()));
      if (thread == null) {
        thread = new Thread(this::run, "stone-sound");
        thread.setDaemon(true);
        thread.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 3));
        thread.start();
      }
      lock.notifyAll();
    }
  }

  /** A request nothing newer of its kind followed for {@link #SETTLE_NANOS}. Holds the lock. */
  String takeSettled(long now) {
    for (var it = pending.values().iterator(); it.hasNext(); ) {
      Request r = it.next();
      if (now - r.at() >= SETTLE_NANOS) {
        it.remove();
        return r.wav();
      }
    }
    return null;
  }

  /** How long until the next pending request settles; Long.MAX_VALUE when none. Holds the lock. */
  private long nanosUntilSettled(long now) {
    long wait = Long.MAX_VALUE;
    for (Request r : pending.values()) wait = Math.min(wait, SETTLE_NANOS - (now - r.at()));
    return wait;
  }

  /** For tests: records a request made at {@code at} without starting the audio thread. */
  void requestAt(String kind, String wav, long at) {
    synchronized (lock) {
      pending.put(kind, new Request(wav, at));
    }
  }

  String takeSettledForTest(long now) {
    synchronized (lock) {
      return takeSettled(now);
    }
  }

  private void run() {
    try {
      while (true) {
        String wav = awaitWork();
        if (wav != null) startVoice(wav);
        if (!voices.isEmpty()) feedLine();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      closeLine();
    }
  }

  /**
   * Waits until a request has settled or the line wants more audio; returns the request or null.
   * While nothing plays, it sleeps without waking up, and closes the line after a long silence.
   */
  private String awaitWork() throws InterruptedException {
    synchronized (lock) {
      while (true) {
        long now = System.nanoTime();
        String wav = takeSettled(now);
        if (wav != null) return wav;
        long wait;
        if (!voices.isEmpty()) {
          wait = nanosUntilLineNeedsAudio();
          if (wait <= 0) return null;
          wait = Math.min(wait, nanosUntilSettled(now));
        } else if (!pending.isEmpty()) {
          wait = nanosUntilSettled(now);
        } else if (line != null) {
          wait = IDLE_CLOSE_NANOS - (now - idleSince);
          if (wait <= 0) {
            closeLine();
            continue;
          }
        } else {
          lock.wait();
          continue;
        }
        java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(lock, Math.max(1_000_000L, wait));
      }
    }
  }

  private void startVoice(String wav) {
    Pcm pcm;
    try {
      pcm = cache.get(wav);
      if (pcm == null) {
        pcm = decoder.decode(wav);
        if (pcm != null) cache.put(wav, pcm);
      }
    } catch (Exception e) {
      SoundPlayer.reportFailure(e);
      return;
    }
    if (pcm == null) {
      fallback.accept(wav);
      return;
    }
    if (line == null || !sameFormat(lineFormat, pcm.format())) {
      if (!voices.isEmpty()) {
        fallback.accept(wav); // another format while sounds play: play this one on its own
        return;
      }
      if (!openLine(pcm.format())) return;
    }
    if (!line.isRunning()) {
      line.start();
      // Start at the steady-state fill, so the first sound comes as late as the following ones.
      writeSilence(frames(TARGET_MS - CHUNK_MS));
    }
    int fade = frames(FADE_MS);
    int playing = 0;
    for (Voice v : voices) if (!v.fading()) playing++;
    for (int i = 0; playing >= MAX_VOICES && i < voices.size(); i++) {
      if (!voices.get(i).fading()) {
        voices.get(i).fadeOut(fade); // the oldest stops, softly, without a click
        playing--;
      }
    }
    voices.add(new Voice(pcm.samples(), lineFormat.getChannels()));
  }

  /** Mixes and writes one chunk, or several until the line holds the target fill. */
  private void feedLine() {
    try {
      while (!voices.isEmpty() && nanosUntilLineNeedsAudio() <= 0) {
        int samples = frames(CHUNK_MS) * lineFormat.getChannels();
        if (mixBuffer.length != samples) {
          mixBuffer = new short[samples];
          byteBuffer = new byte[samples * 2];
        }
        mix(voices, mixBuffer);
        for (int i = 0; i < samples; i++) {
          byteBuffer[2 * i] = (byte) mixBuffer[i];
          byteBuffer[2 * i + 1] = (byte) (mixBuffer[i] >> 8);
        }
        line.write(byteBuffer, 0, byteBuffer.length);
      }
      if (voices.isEmpty()) {
        line.drain();
        line.stop();
        idleSince = System.nanoTime();
      }
    } catch (RuntimeException e) {
      SoundPlayer.reportFailure(e);
      voices.clear();
      closeLine(); // a device that went away may be back for the next move
    }
  }

  /** Adds the voices into {@code out} (cleared first), dropping the ones that finished. */
  static void mix(List<Voice> voices, short[] out) {
    int[] sum = new int[out.length];
    for (int i = voices.size() - 1; i >= 0; i--) {
      if (!voices.get(i).addTo(sum)) voices.remove(i);
    }
    for (int i = 0; i < out.length; i++) {
      out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sum[i]));
    }
  }

  private long nanosUntilLineNeedsAudio() {
    if (line == null) return 0;
    int queued = line.getBufferSize() - line.available();
    int target = frames(TARGET_MS) * lineFormat.getFrameSize();
    if (queued < target) return 0;
    double bytesPerNano = lineFormat.getFrameRate() * lineFormat.getFrameSize() / 1e9;
    return (long) ((queued - target) / bytesPerNano);
  }

  private boolean openLine(AudioFormat format) {
    closeLine();
    try {
      SourceDataLine l = lines.openLine(format);
      try {
        // Room for a little more than the target fill; most systems round this up.
        int bytes = frames(format, TARGET_MS * 2) * format.getFrameSize();
        l.open(format, bytes);
      } catch (Exception e) {
        l.close();
        throw e;
      }
      line = l;
      lineFormat = format;
      return true;
    } catch (Exception e) {
      SoundPlayer.reportFailure(e);
      return false;
    }
  }

  private void closeLine() {
    if (line != null) {
      try {
        line.close();
      } catch (RuntimeException ignored) {
        // closing a line of a device that went away
      }
      line = null;
      lineFormat = null;
    }
  }

  private void writeSilence(int frames) {
    if (frames <= 0) return;
    byte[] silence = new byte[frames * lineFormat.getFrameSize()];
    line.write(silence, 0, silence.length);
  }

  private int frames(int millis) {
    return frames(lineFormat, millis);
  }

  private static int frames(AudioFormat format, int millis) {
    return Math.max(1, Math.round(format.getFrameRate() * millis / 1000f));
  }

  static boolean sameFormat(AudioFormat a, AudioFormat b) {
    return a != null
        && b != null
        && a.getSampleRate() == b.getSampleRate()
        && a.getChannels() == b.getChannels()
        && a.getSampleSizeInBits() == b.getSampleSizeInBits()
        && a.getEncoding().equals(b.getEncoding())
        && a.isBigEndian() == b.isBigEndian();
  }

  /** One sound being played. */
  static final class Voice {
    private final short[] samples;
    private final int channels;
    private int position;
    private int fadeTotal;
    private int fadeLeft = -1;

    Voice(short[] samples, int channels) {
      this.samples = samples;
      this.channels = channels;
    }

    boolean fading() {
      return fadeLeft >= 0;
    }

    /** Ends the sound with a linear fade over {@code frames} frames. */
    void fadeOut(int frames) {
      fadeTotal = Math.max(1, frames);
      fadeLeft = fadeTotal;
    }

    /** Adds the next samples; false once the sound has ended. */
    boolean addTo(int[] sum) {
      for (int i = 0; i < sum.length; i += channels) {
        if (position >= samples.length || fadeLeft == 0) return false;
        double gain = fadeLeft > 0 ? (double) fadeLeft / fadeTotal : 1;
        for (int c = 0; c < channels && position < samples.length; c++) {
          sum[i + c] += (int) Math.round(samples[position++] * gain);
        }
        if (fadeLeft > 0) fadeLeft--;
      }
      return position < samples.length && fadeLeft != 0;
    }
  }
}
