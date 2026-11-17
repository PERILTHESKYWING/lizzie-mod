package featurecat.lizzie.gui;

import featurecat.lizzie.util.KataGoAssetCatalog;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadCancelledException;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadSession;
import featurecat.lizzie.util.KataGoAutoSetupHelper.RemoteWeightInfo;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import featurecat.lizzie.util.KataGoModelAdvisor;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import java.awt.BorderLayout;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Settings → Update AI model: finds the best safe official network ({@link KataGoModelAdvisor}),
 * asks before changing anything, downloads and verifies it with the existing downloader, measures
 * its speed with the existing KataGo benchmark, and switches through KataGo Auto Setup, which keeps
 * the current network when the engine cannot start with the new one. Nothing is deleted.
 */
final class KataGoModelUpgradeFlow {
  private static final Logger LOGGER = LoggerFactory.getLogger(KataGoModelUpgradeFlow.class);

  private final Window owner;

  /** Switches the engine to a downloaded, verified network (KataGo Auto Setup does the work). */
  private final Consumer<Path> switcher;

  private final ResourceBundle bundle = ResourceBundle.getBundle("l10n.DisplayStrings");
  private JDialog progressDialog;
  private JLabel progressLabel;
  private JProgressBar progressBar;
  private volatile DownloadSession session;

  KataGoModelUpgradeFlow(Window owner, Consumer<Path> switcher) {
    this.owner = owner;
    this.switcher = switcher;
  }

  /** Starts the check; call on the event thread. */
  void start() {
    showProgress(text("checking"));
    background(
        () -> {
          SetupSnapshot snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
          if (!snapshot.hasEngine()) {
            onEdt(
                () -> {
                  closeProgress();
                  message(text("noLocalEngine"), JOptionPane.INFORMATION_MESSAGE);
                });
            return;
          }
          String version = "";
          try {
            version =
                KataGoAutoSetupHelper.validateLocalEngine(snapshot.enginePath, 30).kataGoVersion;
          } catch (RuntimeException e) {
            LOGGER.info("KataGo version check failed; assuming the bundled version", e);
          }
          List<RemoteWeightInfo> rows = KataGoAutoSetupHelper.fetchOfficialWeights();
          String current =
              snapshot.activeWeightPath == null
                  ? ""
                  : snapshot.activeWeightPath.getFileName().toString();
          Path weights = snapshot.workingDir.resolve("weights");
          long usable = -1;
          try {
            Files.createDirectories(weights);
            usable = Files.getFileStore(weights).getUsableSpace();
          } catch (java.io.IOException e) {
            LOGGER.info("Free space of {} unknown", weights, e);
          }
          KataGoModelAdvisor.Advice advice =
              KataGoModelAdvisor.advise(
                  rows,
                  version == null ? "" : version,
                  KataGoAssetCatalog.get().katagoVersion(),
                  current,
                  usable);
          boolean currentIsBest = KataGoModelAdvisor.currentIsBest(rows, current);
          onEdt(() -> offer(snapshot, current, advice, currentIsBest));
        });
  }

  private void offer(
      SetupSnapshot snapshot,
      String current,
      KataGoModelAdvisor.Advice advice,
      boolean currentIsBest) {
    closeProgress();
    String currentName =
        current.isEmpty() ? "—" : KataGoAutoSetupHelper.resolveWeightDisplayName(current);
    if (currentIsBest || (!advice.hasCandidate() && !current.isEmpty())) {
      message(format("upToDate", currentName), JOptionPane.INFORMATION_MESSAGE);
      return;
    }
    if (!advice.hasCandidate()) {
      message(text("noCandidate"), JOptionPane.WARNING_MESSAGE);
      return;
    }
    RemoteWeightInfo best = advice.best();
    int answer =
        JOptionPane.showConfirmDialog(
            owner,
            format("confirm", currentName, displayName(best), describe(best)),
            text("title"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE);
    if (answer == JOptionPane.OK_OPTION) install(snapshot, advice, best);
  }

  private void install(
      SetupSnapshot snapshot, KataGoModelAdvisor.Advice advice, RemoteWeightInfo target) {
    String name = displayName(target);
    DownloadSession current = new DownloadSession();
    session = current;
    showProgress(format("downloading", name));
    background(
        () -> {
          Path file =
              KataGoAutoSetupHelper.downloadWeight(
                  target, (status, done, total) -> onEdt(() -> setProgress(done, total)), current);
          onEdt(() -> showProgress(format("benchmarking", name)));
          double visits;
          try {
            visits =
                KataGoRuntimeHelper.runBenchmark(
                        snapshot.withActiveWeight(file), (s, d, t) -> {}, current)
                    .visitsPerSecond;
          } catch (DownloadCancelledException e) {
            throw e;
          } catch (java.io.IOException | RuntimeException e) {
            LOGGER.warn("Benchmark of {} failed", file, e);
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            onEdt(
                () -> {
                  closeProgress();
                  message(format("benchmarkFailed", name, reason), JOptionPane.WARNING_MESSAGE);
                });
            return;
          }
          onEdt(() -> decide(snapshot, advice, target, file, visits));
        });
  }

  private void decide(
      SetupSnapshot snapshot,
      KataGoModelAdvisor.Advice advice,
      RemoteWeightInfo target,
      Path file,
      double visits) {
    closeProgress();
    String name = displayName(target);
    if (visits < KataGoModelAdvisor.MIN_COMFORTABLE_VISITS_PER_SECOND) {
      RemoteWeightInfo lighter = advice.lighterThan(target);
      String speed = String.format(Locale.ROOT, "%.0f", visits);
      String comfortable =
          String.format(Locale.ROOT, "%.0f", KataGoModelAdvisor.MIN_COMFORTABLE_VISITS_PER_SECOND);
      List<Object> options = new ArrayList<>();
      options.add(text("useAnyway"));
      if (lighter != null) options.add(text("tryLighter"));
      options.add(text("cancel"));
      String question =
          lighter == null
              ? format("slowNoLighter", name, speed, comfortable)
              : format("slow", name, speed, comfortable, displayName(lighter));
      int choice =
          JOptionPane.showOptionDialog(
              owner,
              question,
              text("title"),
              JOptionPane.DEFAULT_OPTION,
              JOptionPane.WARNING_MESSAGE,
              null,
              options.toArray(),
              options.get(lighter == null ? 0 : 1));
      if (lighter != null && choice == 1) {
        install(snapshot, advice, lighter);
        return;
      }
      if (choice != 0) {
        message(text("cancelled"), JOptionPane.INFORMATION_MESSAGE);
        return;
      }
    }
    message(format("switching", name), JOptionPane.INFORMATION_MESSAGE);
    switcher.accept(file);
  }

  // ------------------------------------------------------------------------------ helpers

  private static String displayName(RemoteWeightInfo info) {
    return KataGoAutoSetupHelper.resolveRemoteWeightDisplayName(info);
  }

  private String describe(RemoteWeightInfo info) {
    List<String> parts = new ArrayList<>();
    if (info.eloRating != null && !info.eloRating.isBlank()) parts.add("Elo " + info.eloRating);
    parts.add(String.format(Locale.ROOT, "%.0f MB", info.sizeBytes / 1024.0 / 1024.0));
    if (info.uploadedAt != null && !info.uploadedAt.isBlank()) parts.add(info.uploadedAt);
    if (!info.minimumKataGoVersion.isEmpty()) parts.add("KataGo ≥ " + info.minimumKataGoVersion);
    return String.join(" · ", parts);
  }

  private interface Work {
    void run() throws Exception;
  }

  private void background(Work work) {
    Thread t =
        new Thread(
            () -> {
              try {
                work.run();
              } catch (DownloadCancelledException e) {
                onEdt(
                    () -> {
                      closeProgress();
                      message(text("cancelled"), JOptionPane.INFORMATION_MESSAGE);
                    });
              } catch (Exception e) {
                LOGGER.warn("AI model update failed", e);
                String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                onEdt(
                    () -> {
                      closeProgress();
                      message(format("failed", reason), JOptionPane.WARNING_MESSAGE);
                    });
              }
            },
            "katago-model-update");
    t.setDaemon(true);
    t.start();
  }

  private static void onEdt(Runnable r) {
    SwingUtilities.invokeLater(r);
  }

  private void showProgress(String status) {
    if (progressDialog == null) {
      progressDialog = new JDialog(owner, text("title"));
      progressLabel = new JLabel(status);
      progressBar = new JProgressBar();
      progressBar.setIndeterminate(true);
      JButton cancel = new JButton(text("cancel"));
      cancel.addActionListener(
          e -> {
            DownloadSession s = session;
            if (s != null) s.cancel();
            closeProgress();
          });
      JPanel panel = new JPanel(new BorderLayout(8, 8));
      panel.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
      panel.add(progressLabel, BorderLayout.NORTH);
      panel.add(progressBar, BorderLayout.CENTER);
      panel.add(cancel, BorderLayout.EAST);
      progressDialog.setContentPane(panel);
      progressDialog.setSize(460, 120);
      progressDialog.setLocationRelativeTo(owner);
    }
    progressLabel.setText(status);
    progressBar.setIndeterminate(true);
    progressDialog.setVisible(true);
  }

  private void setProgress(long done, long total) {
    if (progressBar == null || total <= 0) return;
    progressBar.setIndeterminate(false);
    progressBar.setMaximum(1000);
    progressBar.setValue((int) Math.min(1000, done * 1000 / total));
  }

  private void closeProgress() {
    if (progressDialog != null) {
      progressDialog.dispose();
      progressDialog = null;
    }
  }

  private void message(String text, int type) {
    JOptionPane.showMessageDialog(owner, text, text("title"), type);
  }

  private String text(String key) {
    try {
      return bundle.getString("ModelUpdate." + key);
    } catch (MissingResourceException e) {
      return key;
    }
  }

  private String format(String key, Object... args) {
    return MessageFormat.format(text(key), args);
  }
}
