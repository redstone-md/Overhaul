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
 * the low bytes and the light level times seventeen in the high one, or one there for a fluid and two for a solid
 * block. A block that gives light carries the colour of its light, taken from the bright part of its texture; a solid
 * one its own colour as maps show it, for the paths of light that turn on it; any other carries what it lets through:
 * white for air, and its own colour, paled, for glass, ice, leaves and water. Answers are kept per block
 * state and per texture, so a texture is read once.
 */
final class BlazeLightColors {
    /** Air: every colour passes, no light of its own. */
    static final int CLEAR = 0x00FFFFFF;
    /** The high byte of a solid block's word. */
    private static final int SOLID = 2;
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
        if (state.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) >= 15) {
            int own = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
            if (own == 0) own = 0x7F7F7F;
            return pack((own >> 16 & 255) / 255f, (own >> 8 & 255) / 255f, (own & 255) / 255f) | SOLID << 24;
        }
        Block block = state.getBlock();
        boolean fluid = !state.getFluidState().isEmpty(), leaves = block instanceof LeavesBlock;
        if (!fluid && !leaves && !(block instanceof HalfTransparentBlock) && !(block instanceof StainedGlassPaneBlock)) return CLEAR;
        int colour = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
        if (colour == 0) return CLEAR;
        float keep = leaves ? 0.45f : fluid ? 0.5f : 0.9f;
        return pack(pale(colour >> 16, keep), pale(colour >> 8, keep), pale(colour, keep)) | (fluid ? 1 << 24 : 0);
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

    /**
     * The colour of a texture's bright pixels at full strength. A flame's picture is yellower than the light it gives,
     * so a colour that runs red, green, blue from strong to weak is drawn half way to the warm light of fire.
     */
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
                double weight = (pixel >>> 24) / 255.0 * most * most;
                red += r * weight;
                green += g * weight;
                blue += b * weight;
                total += weight;
            }
            if (total <= 0) return WARM;
            double most = Math.max(red, Math.max(green, blue));
            double r = red / most, g = green / most, b = blue / most, fire = r >= g && g >= b ? 0.5 : 0;
            return pack(shade(r + (1 - r) * fire), shade(g + (0.62 - g) * fire), shade(b + (0.36 - b) * fire));
        } catch (java.io.IOException | RuntimeException unreadable) {
            return WARM;
        }
    }

    /** No light is so pure that a channel is missing from it. */
    private static float shade(double channel) {
        return (float) Math.max(0.12, Math.min(1, channel));
    }

    private static int pack(float red, float green, float blue) {
        return Math.round(red * 255) | Math.round(green * 255) << 8 | Math.round(blue * 255) << 16;
    }
}
