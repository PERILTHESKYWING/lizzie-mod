package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ResourceBundle;
import java.util.Set;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The tidy default toolbar: only the everyday buttons until the user picks their own. */
class TopBarDefaultsTest {
  @TempDir Path tmp;

  private static String text(String key) {
    return ResourceBundle.getBundle("l10n.DisplayStrings").getString(key);
  }

  private static TopHeaderPanel bar(Path file) {
    return new TopHeaderPanel(file, TopHeaderPanel.defaultShown());
  }

  @Test
  void onlyTheEverydayButtonsShowByDefault() {
    TopHeaderPanel bar = bar(tmp.resolve("a.json"));
    JButton fox = new JButton(text("Menu.foxKifuButton"));
    JButton tencent = new JButton("Tencent");
    JPanel komi = new JPanel();
    komi.add(new JLabel(text("Menu.komi")));
    JButton hawk = new JButton();
    hawk.setToolTipText(text("Menu.btnHawkeye.toolTipText"));
    JButton tsumego = new JButton(text("Menu.tsumeGoBtn"));
    for (java.awt.Component c : List.of(fox, tencent, komi, hawk, tsumego)) bar.add(c);
    bar.customization().apply(bar.content());
    assertTrue(fox.isVisible());
    assertTrue(komi.isVisible());
    assertTrue(hawk.isVisible());
    assertFalse(tencent.isVisible());
    assertFalse(tsumego.isVisible());
    assertEquals(TopHeaderPanel.DEFAULT_SHOWN_KEYS.size(), TopHeaderPanel.defaultShown().size());
    assertTrue(TopHeaderPanel.defaultShown().contains(text("TopBar.updateModel")));
  }

  @Test
  void anOlderHiddenListGivesWayToTheDefaultsButAPickedListIsKept() throws Exception {
    Path file = tmp.resolve("top-toolbar.json");
    Files.writeString(file, "{\"hidden\":[\"Fox Kifu\"]}", StandardCharsets.UTF_8);
    TopBarCustomization old = new TopBarCustomization(file, Set.of("Fox Kifu", "Rules"));
    assertFalse(old.isHidden("Fox Kifu"), "the new default set replaces the old list");
    assertTrue(old.isHidden("Tencent"));

    old.setShown(Set.of("Rules", "Tencent"));
    TopBarCustomization reloaded = new TopBarCustomization(file, Set.of("Fox Kifu"));
    assertTrue(reloaded.isHidden("Fox Kifu"), "the user's own pick wins over the defaults");
    assertFalse(reloaded.isHidden("Tencent"));
    assertTrue(Files.readString(file).contains("\"version\": 2"));
    assertFalse(reloaded.isHidden(""), "unnamed items always stay");
  }

  @Test
  void thePanelHoldingKomiIsNamedKomi() {
    JPanel komi = new JPanel();
    komi.add(new JLabel("PDA:"));
    komi.putClientProperty(TopBarCustomization.LABEL, text("Menu.komi"));
    assertEquals(text("Menu.komi"), TopBarCustomization.labelOf(komi));
  }

  @Test
  void pdaAndBreadthLeaveTheToolbarOnceButCanBeShownAgain() {
    org.json.JSONObject ui = new org.json.JSONObject().put("show-pda-in-menu", true);
    featurecat.lizzie.Config config =
        featurecat.lizzie.ConfigTestHelper.createForTests(tmp.resolve("work"));
    assertFalse(config.showPDAInMenu, "a new or older profile starts with the tidy toolbar");
    assertFalse(config.showWRNInMenu);
    assertEquals(2, config.uiConfig.optInt("toolbar-layout"));
    assertTrue(ui.optBoolean("show-pda-in-menu"));
  }
}
