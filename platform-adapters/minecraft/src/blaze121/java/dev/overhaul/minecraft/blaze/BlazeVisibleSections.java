package dev.overhaul.minecraft.blaze;

import dev.overhaul.minecraft.blaze.mixin.CompiledSection121Accessor;
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
    /** By chunk layer id: this is asked several thousand times a frame. */
    private Layer[] layers=new Layer[8];
    private final Layer withBlockEntities=new Layer();
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
        for(Layer layer:layers)if(layer!=null)layer.cursor=0;
        withBlockEntities.cursor=0;
        for(int index=0,size=visible.size();index<size;index++){
            RenderSection section=visible.get(index);
            var compiled=section.getCompiled();
            if(!compiled.getRenderableBlockEntities().isEmpty())withBlockEntities.put(section);
            for(RenderType type:((CompiledSection121Accessor)compiled).overhaul$hasBlocks()){
                layerOf(type.getChunkLayerId()).put(section);
            }
        }
        for(Layer layer:layers)if(layer!=null)layer.sections.size(layer.cursor);
        withBlockEntities.sections.size(withBlockEntities.cursor);
        source=visible;
        scanned=visible.size();
    }

    /** A list swapped or changed since the scan is walked whole, as vanilla would. */
    public ObjectArrayList<RenderSection> layer(ObjectArrayList<RenderSection> visible,RenderType type){
        if(!scanned(visible))return visible;
        int id=type.getChunkLayerId();
        Layer layer=id<0||id>=layers.length?null:layers[id];
        return layer==null?NONE:layer.sections;
    }

    /** A section compiled after the scan shows its block entities a frame later, as its mesh does. */
    public ObjectArrayList<RenderSection> blockEntitySections(ObjectArrayList<RenderSection> visible){
        return scanned(visible)?withBlockEntities.sections:visible;
    }

    private Layer layerOf(int id){
        if(id>=layers.length)layers=java.util.Arrays.copyOf(layers,id+1);
        Layer layer=layers[id];
        if(layer==null)layers[id]=layer=new Layer();
        return layer;
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
