package dev.overhaul.minecraft.backendmixin;

import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.List;
import java.util.Map;

/** Internal read-only access; the cache snapshots identities and never mutates these retained collections. */
@Mixin(ModelPart.class)
public interface ModelPartStructureAccess {
    @Accessor("cubes") List<ModelPart.Cube> overhaul$cubes();
    @Accessor("children") Map<String,ModelPart> overhaul$children();
}
