package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * What a block is to coloured light, as one word a {@link BlazeLightVolume} stores per block: red, green and blue in
 * the low bytes and the light level times seventeen in the high one. A block that gives light carries the colour of
 * its light, taken from the bright part of its texture; any other carries what it lets through: white for air,
 * nothing for a solid block, and its own colour, paled, for glass, ice, leaves and water. Answers are kept per block
 * state and per texture, so a texture is read once.
 */
final class BlazeLightColors {
    /** Air: every colour passes, no light of its own. */
    static final int CLEAR = 0x00FFFFFF;
    /** Never a block's word, so it can mean a state not asked about yet. */
    private static final int UNKNOWN = 1;
    private static final int WARM = pack(1f, 0.66f, 0.38f);
    private static final Reference2IntOpenHashMap<BlockState> STATES = new Reference2IntOpenHashMap<>();
    private static final Map<ResourceLocation, Integer> TEXTURES = new HashMap<>();

    static {
        STATES.defaultReturnValue(UNKNOWN);
    }

    private BlazeLightColors() {}

    static int of(BlockState state) {
        int known = STATES.getInt(state);
        if (known != UNKNOWN) return known;
        int word = read(state);
        STATES.put(state, word);
        return word;
    }

    /** Textures may have changed: every answer is worked out again. */
    static void forget() {
        STATES.clear();
        TEXTURES.clear();
    }

    private static int read(BlockState state) {
        if (state.isAir()) return CLEAR;
        int level = Math.min(15, state.getLightEmission());
        if (level > 0) return glow(state) | level * 17 << 24;
        if (state.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) >= 15) return 0;
        Block block = state.getBlock();
        boolean fluid = !state.getFluidState().isEmpty(), leaves = block instanceof LeavesBlock;
        if (!fluid && !leaves && !(block instanceof HalfTransparentBlock) && !(block instanceof StainedGlassPaneBlock)) return CLEAR;
        int colour = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
        if (colour == 0) return CLEAR;
        float keep = leaves ? 0.35f : 0.5f;
        return pack(pale(colour >> 16, keep), pale(colour >> 8, keep), pale(colour, keep));
    }

    private static float pale(int channel, float keep) {
        return 1 - keep + keep * (channel & 255) / 255f;
    }

    private static int glow(BlockState state) {
        try {
            TextureAtlasSprite sprite = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getParticleIcon(state);
            ResourceLocation name = sprite.contents().name();
            Integer known = TEXTURES.get(name);
            if (known == null) TEXTURES.put(name, known = bright(name));
            return known;
        } catch (RuntimeException unreadable) {
            return WARM;
        }
    }

    /** The colour of a texture's bright pixels, at full strength and richer than the file holds it. */
    private static int bright(ResourceLocation name) {
        var resource = Minecraft.getInstance().getResourceManager().getResource(name.withPath(path -> "textures/" + path + ".png"));
        if (resource.isEmpty()) return WARM;
        try (var stream = resource.get().open(); NativeImage image = NativeImage.read(stream)) {
            // An animated texture stacks its frames; the first one tells the colour.
            int width = image.getWidth(), height = Math.min(image.getHeight(), width);
            double red = 0, green = 0, blue = 0, total = 0;
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int pixel = image.getPixelRGBA(x, y);
                double r = (pixel & 255) / 255.0, g = (pixel >> 8 & 255) / 255.0, b = (pixel >> 16 & 255) / 255.0, most = Math.max(r, Math.max(g, b));
                double weight = (pixel >>> 24) / 255.0 * most * most * most * most;
                red += r * weight;
                green += g * weight;
                blue += b * weight;
                total += weight;
            }
            if (total <= 0) return WARM;
            double most = Math.max(red, Math.max(green, blue)), grey = (0.3 * red + 0.59 * green + 0.11 * blue) / most;
            return pack(rich(red / most, grey), rich(green / most, grey), rich(blue / most, grey));
        } catch (java.io.IOException | RuntimeException unreadable) {
            return WARM;
        }
    }

    private static float rich(double channel, double grey) {
        return (float) Math.max(0.02, Math.min(1, grey + (channel - grey) * 1.7));
    }

    private static int pack(float red, float green, float blue) {
        return Math.round(red * 255) | Math.round(green * 255) << 8 | Math.round(blue * 255) << 16;
    }
}
