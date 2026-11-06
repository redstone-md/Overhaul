package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeSectionGrid;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Every time the camera crosses a section the game compares the origin of each section in the grid, some fifteen
 * thousand of them, and moves only the few columns that wrapped around. A column's origin depends on X and Z alone,
 * so the origins are worked out once per axis and the sections of unchanged columns are not visited.
 */
@Mixin(ViewArea.class)
abstract class ViewAreaColumns121Mixin implements BlazeSectionGrid {
    @Unique private static final int UNKNOWN = Integer.MIN_VALUE;

    @Shadow @Final protected Level level;
    @Shadow protected int sectionGridSizeY;
    @Shadow protected int sectionGridSizeX;
    @Shadow protected int sectionGridSizeZ;
    @Shadow public SectionRenderDispatcher.RenderSection[] sections;
    @Shadow protected abstract int getSectionIndex(int x, int y, int z);

    @Unique private int[] overhaul$columnX = new int[0];
    @Unique private int[] overhaul$columnZ = new int[0];

    @Override public int overhaul$gridWidth() { return sectionGridSizeX; }
    @Override public int overhaul$gridHeight() { return sectionGridSizeY; }
    @Override public int overhaul$gridDepth() { return sectionGridSizeZ; }

    @Inject(method = "repositionCamera", at = @At("HEAD"), cancellable = true, require = 1)
    private void overhaul$moveWrappedColumns(double cameraX, double cameraZ, CallbackInfo callback) {
        callback.cancel();
        if (overhaul$columnX.length != sectionGridSizeX || overhaul$columnZ.length != sectionGridSizeZ) {
            overhaul$columnX = new int[sectionGridSizeX];
            overhaul$columnZ = new int[sectionGridSizeZ];
            Arrays.fill(overhaul$columnX, UNKNOWN);
            Arrays.fill(overhaul$columnZ, UNKNOWN);
        }
        int width = sectionGridSizeX * 16;
        int startX = Mth.ceil(cameraX) - 8 - width / 2;
        int depth = sectionGridSizeZ * 16;
        int startZ = Mth.ceil(cameraZ) - 8 - depth / 2;
        int minY = level.getMinBuildHeight();
        for (int x = 0; x < sectionGridSizeX; x++) {
            int originX = startX + Math.floorMod(x * 16 - startX, width);
            boolean movedX = overhaul$columnX[x] != originX;
            overhaul$columnX[x] = originX;
            for (int z = 0; z < sectionGridSizeZ; z++) {
                int originZ = startZ + Math.floorMod(z * 16 - startZ, depth);
                if (!movedX && overhaul$columnZ[z] == originZ) continue;
                for (int y = 0; y < sectionGridSizeY; y++) {
                    int originY = minY + y * 16;
                    SectionRenderDispatcher.RenderSection section = sections[getSectionIndex(x, y, z)];
                    var origin = section.getOrigin();
                    if (originX != origin.getX() || originY != origin.getY() || originZ != origin.getZ()) {
                        section.setOrigin(originX, originY, originZ);
                    }
                }
            }
        }
        for (int z = 0; z < sectionGridSizeZ; z++) {
            overhaul$columnZ[z] = startZ + Math.floorMod(z * 16 - startZ, depth);
        }
    }
}
