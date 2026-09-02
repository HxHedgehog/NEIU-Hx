package codechicken.nei.config;

import codechicken.nei.Image;
import codechicken.nei.LayoutManager;
import codechicken.nei.NEIModContainer;

public class OptionGamemodes extends OptionStringSet {

    public OptionGamemodes(String name) {
        super(name);

        options.add("creative");
        options.add("creative+");
        options.add("adventure");
        // Gate on whether EtFR is installed rather than on its spectator GameType: this option is
        // constructed from NEIClientConfig's static initializer, which can run before EtFR's preInit
        // initializes SPECTATOR_GAMETYPE. The mod list is already available at that point, and
        // isValidGamemode() re-checks the GameType anyway, so the button is only ever functional when
        // EtFR actually provides spectator mode.
        if (NEIModContainer.isEtFuturumLoaded()) options.add("spectator");
    }

    @Override
    public void drawIcons() {
        int x = buttonX();
        LayoutManager.drawIcon(x + 4, 4, new Image(20, 4, 12, 12));
        x += 24;
        LayoutManager.drawIcon(x + 4, 4, new Image(52, 4, 12, 12));
        x += 24;
        LayoutManager.drawIcon(x + 4, 4, new Image(68, 4, 12, 12));
        if (NEIModContainer.isEtFuturumLoaded()) {
            x += 24;
            LayoutManager.drawIcon(x + 4, 4, new Image(100, 4, 12, 12));
        }
    }
}
