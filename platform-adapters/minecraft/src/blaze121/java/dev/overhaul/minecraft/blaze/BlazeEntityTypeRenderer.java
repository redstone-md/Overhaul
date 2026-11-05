package dev.overhaul.minecraft.blaze;

import java.util.Map;
import net.minecraft.client.renderer.entity.EntityRenderer;

/** The renderer of an entity type, valid only for the renderer map it was read from. */
public interface BlazeEntityTypeRenderer {
    Map<?,?> overhaul$rendererSource();
    EntityRenderer<?> overhaul$renderer();
    void overhaul$renderer(Map<?,?> source,EntityRenderer<?> renderer);
}
