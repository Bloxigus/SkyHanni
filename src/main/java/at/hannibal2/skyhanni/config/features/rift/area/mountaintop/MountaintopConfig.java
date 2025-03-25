package at.hannibal2.skyhanni.config.features.rift.area.mountaintop;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;

public class MountaintopConfig {

    // Quad Link Legacy

    @ConfigOption(name = "Quad Link Legacy", desc = "")
    @Accordion
    @Expose
    public QuadLinkLegacyConfig quadLinkLegacy = new QuadLinkLegacyConfig();

}
