package dev.overhaul.minecraft.legacy;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import java.util.Map;

@IFMLLoadingPlugin.Name("overhaul")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.SortingIndex(1001)
@IFMLLoadingPlugin.TransformerExclusions({"dev.overhaul.minecraft.legacy.","dev.overhaul.adapter.","dev.overhaul.core."})
public final class OverhaulCorePlugin implements IFMLLoadingPlugin {
    @Override public String[] getASMTransformerClass() {
        return new String[]{"dev.overhaul.minecraft.legacy.LifecycleTransformer"};
    }
    @Override public String getModContainerClass() { return null; }
    @Override public String getSetupClass() { return null; }
    @Override public void injectData(Map<String,Object> data) { }
    @Override public String getAccessTransformerClass() { return null; }
}
