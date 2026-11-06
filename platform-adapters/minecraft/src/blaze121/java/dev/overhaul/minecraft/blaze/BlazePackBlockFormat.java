package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.overhaul.shader.pack.PackIdMap;
import java.util.Arrays;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * The game's block vertex followed by the pack's block id and render type, both shorts. Sections build in it only while
 * a pack with block.properties draws the world; the first 32 bytes stay the block layout, so every other reader works.
 */
public final class BlazePackBlockFormat {
    /** Iris's mc_Entity.y values: packs test it to tell fluid faces from block faces. */
    public static final short BLOCK = -1, FLUID = 1;
    public static final int ID_OFFSET = 32;
    public static final VertexFormatElement ELEMENT = register();
    public static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV0", VertexFormatElement.UV0)
            .add("UV2", VertexFormatElement.UV2)
            .add("Normal", VertexFormatElement.NORMAL)
            .padding(1)
            .add(BlazePackBlockIds.FIELD, ELEMENT)
            .build();
    private static final short UNSET = Short.MIN_VALUE;
    private static volatile Table table;

    private record Table(PackIdMap ids, short[] values) {}

    private BlazePackBlockFormat() {}

    /** Takes the highest free element id: mods that add elements pick low fixed ids. UV usage admits any index. */
    private static VertexFormatElement register() {
        for (int id = VertexFormatElement.MAX_COUNT - 1; id >= 0; id--) {
            if (VertexFormatElement.byId(id) == null) return VertexFormatElement.register(id, 3, VertexFormatElement.Type.SHORT, VertexFormatElement.Usage.UV, 2);
        }
        throw new IllegalStateException("No free vertex element id for shader-pack block ids");
    }

    /** The id the active pack gives this state, or -1; computed once per state and pack, from any build thread. */
    public static short id(BlockState state) {
        PackIdMap ids = BlazePackBlockIds.active();
        if (ids == null) return PackIdMap.UNKNOWN;
        Table current = table;
        if (current == null || current.ids() != ids) {
            short[] values = new short[Block.BLOCK_STATE_REGISTRY.size()];
            Arrays.fill(values, UNSET);
            table = current = new Table(ids, values);
        }
        int index = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (index < 0 || index >= current.values().length) return compute(ids, state);
        short value = current.values()[index];
        if (value == UNSET) current.values()[index] = value = compute(ids, state);
        return value;
    }

    private static short compute(PackIdMap ids, BlockState state) {
        int id = ids.block(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), name -> value(state, name));
        return id < 0 || id > Short.MAX_VALUE ? PackIdMap.UNKNOWN : (short) id;
    }

    private static String value(BlockState state, String name) {
        for (Property<?> property : state.getProperties()) if (property.getName().equals(name)) return valueName(state, property);
        return null;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
