package featurecat.lizzie.gui;

import java.util.ResourceBundle;

/**
 * Everyday buttons of the top toolbar that open features otherwise found in the menus: Re-analyse,
 * Update mod, Update AI model and Theme.
 */
final class TopBarActions {
  private TopBarActions() {}

  static void addTo(TopHeaderPanel bar, ResourceBundle bundle, LizzieFrame frame) {
    bar.add(ReanalyzeGameButton.create(bundle, () -> frame.flashAnalyzeGame(true, false)));
    bar.add(button(bundle, "TopBar.updateMod", () -> new ModUpdater(frame).start()));
    bar.add(button(bundle, "TopBar.updateModel", frame::updateKataGoModel));
    bar.add(button(bundle, "TopBar.theme", () -> frame.openConfigDialog2(1)));
  }

  static JFontButton button(ResourceBundle bundle, String key, Runnable action) {
    JFontButton button = new JFontButton(bundle.getString(key));
    button.setFocusable(false);
    String tip = key + ".tip";
    if (bundle.containsKey(tip)) button.setToolTipText(bundle.getString(tip));
    button.setMargin(new java.awt.Insets(0, 0, 1, 0));
    button.addActionListener(e -> action.run());
    return button;
  }
}
