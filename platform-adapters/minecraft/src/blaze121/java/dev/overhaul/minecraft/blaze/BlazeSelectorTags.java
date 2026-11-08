package dev.overhaul.minecraft.blaze;

import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;

/** An entity selector that knows the tags its {@code tag=} options and the type tags its {@code type=#} options require. */
public interface BlazeSelectorTags {
    void overhaul$requiredTags(String[] tags);

    /** The required tags, or null when it requires none. */
    String[] overhaul$requiredTags();

    void overhaul$excludedTags(String[] tags);

    /** The tags its {@code tag=!name} options exclude, or null when it excludes none. */
    String[] overhaul$excludedTags();

    void overhaul$requiredTypeTags(TagKey<EntityType<?>>[] tags);

    /** The required type tags, or null when it requires none. */
    TagKey<EntityType<?>>[] overhaul$requiredTypeTags();

    void overhaul$requiredScores(String[] objectives);

    /** The objectives its {@code scores=} option requires a score in, or null when it requires none. */
    String[] overhaul$requiredScores();
}
