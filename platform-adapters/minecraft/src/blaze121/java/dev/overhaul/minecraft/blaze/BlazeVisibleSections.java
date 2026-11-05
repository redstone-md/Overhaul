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
    private final Object2ObjectOpenHashMap<RenderType,Layer> layers=new Object2ObjectOpenHashMap<>();
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
        for(Layer layer:layers.values())layer.cursor=0;
        for(int index=0,size=visible.size();index<size;index++){
            RenderSection section=visible.get(index);
            for(RenderType type:((CompiledSection121Accessor)section.getCompiled()).overhaul$hasBlocks()){
                layers.computeIfAbsent(type,key->new Layer()).put(section);
            }
        }
        for(Layer layer:layers.values())layer.sections.size(layer.cursor);
        source=visible;
        scanned=visible.size();
    }

    /** A list swapped or changed since the scan is walked whole, as vanilla would. */
    public ObjectArrayList<RenderSection> layer(ObjectArrayList<RenderSection> visible,RenderType type){
        if(!scanned(visible))return visible;
        Layer layer=layers.get(type);
        return layer==null?NONE:layer.sections;
    }

    /** Whether {@link #layer} would answer from a scan, so its sections are known to have geometry in the layer asked about. */
    public boolean scanned(ObjectArrayList<RenderSection> visible){
        return visible==source&&visible.size()==scanned;
    }

    /** Rewrites only changed slots: the order rarely changes between frames, and each reference store pays a GC barrier. */
    private static final class Layer {
        private final ObjectArrayList<RenderSection> sections=new ObjectArrayList<>();
        private int cursor;
        private void put(RenderSection section){
            if(cursor==sections.size())sections.add(section);
            else if(sections.get(cursor)!=section)sections.set(cursor,section);
            cursor++;
        }
    }
}
