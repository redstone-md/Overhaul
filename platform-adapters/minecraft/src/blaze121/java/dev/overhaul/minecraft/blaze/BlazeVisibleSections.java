package dev.overhaul.minecraft.blaze;

import dev.overhaul.minecraft.blaze.mixin.CompiledSection121Accessor;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;

/**
 * Per-frame views of the visible section list, so its long scans run once instead of once per layer.
 * Layer lists hold only sections with geometry in that layer, in visible order. The vanilla per-section checks still run on them.
 */
public final class BlazeVisibleSections {
    private static final ObjectArrayList<RenderSection> NONE=new ObjectArrayList<>(0);
    private final ObjectArrayList<RenderSection> dirty=new ObjectArrayList<>();
    private final Object2ObjectOpenHashMap<RenderType,ObjectArrayList<RenderSection>> layers=new Object2ObjectOpenHashMap<>();
    private ObjectArrayList<RenderSection> source;
    private int scanned;

    public ObjectArrayList<RenderSection> dirty(ObjectArrayList<RenderSection> visible){
        dirty.clear();
        for(int index=0,size=visible.size();index<size;index++){
            RenderSection section=visible.get(index);
            if(section.isDirty())dirty.add(section);
        }
        return dirty;
    }

    /** Runs after pending uploads, so sections finished this frame are already in their layers. */
    public void scan(ObjectArrayList<RenderSection> visible){
        for(ObjectArrayList<RenderSection> sections:layers.values())sections.clear();
        for(int index=0,size=visible.size();index<size;index++){
            RenderSection section=visible.get(index);
            for(RenderType layer:((CompiledSection121Accessor)section.getCompiled()).overhaul$hasBlocks()){
                layers.computeIfAbsent(layer,key->new ObjectArrayList<>()).add(section);
            }
        }
        source=visible;
        scanned=visible.size();
    }

    /** A list swapped or changed since the scan is walked whole, as vanilla would. */
    public ObjectArrayList<RenderSection> layer(ObjectArrayList<RenderSection> visible,RenderType layer){
        if(visible!=source||visible.size()!=scanned)return visible;
        ObjectArrayList<RenderSection> sections=layers.get(layer);
        return sections==null?NONE:sections;
    }
}
