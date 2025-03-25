package at.hannibal2.skyhanni.config.features.rift.area.mountaintop;

import at.hannibal2.skyhanni.config.FeatureToggle;
import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;

public class QuadLinkLegacyConfig {

    @Expose
    @ConfigOption(name = "Enabled", desc = "Enable solver for Quad Link Legacy against Wizardman")
    @ConfigEditorBoolean
    @FeatureToggle
    public boolean enabled = true;
}
