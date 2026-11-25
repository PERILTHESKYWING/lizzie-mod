package featurecat.lizzie.gui;

import java.util.ResourceBundle;

/**
 * The top-toolbar "Re-analyse" button: analyses the whole game again with the current komi and
 * rules. Moves whose analysis predates a komi or rule change are redone (see {@code
 * AnalysisEngine#shouldAnalyzeNode}), so the winrate graph is correct without stepping through the
 * game by hand.
 */
final class ReanalyzeGameButton {
  private ReanalyzeGameButton() {}

  static JFontButton create(ResourceBundle bundle, Runnable reanalyse) {
    JFontButton button = new JFontButton(bundle.getString("Menu.reanalyzeBtn"));
    button.setFocusable(false);
    button.setToolTipText(bundle.getString("Menu.reanalyzeTip"));
    button.setMargin(new java.awt.Insets(0, 0, 1, 0));
    button.addActionListener(e -> reanalyse.run());
    return button;
  }
}
