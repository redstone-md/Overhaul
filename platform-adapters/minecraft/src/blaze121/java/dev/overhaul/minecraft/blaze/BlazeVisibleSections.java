package dev.overhaul.minecraft.blaze;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;

/**
 * Per-frame views of the visible section list, so its long scans run once instead of once per layer.
 * Layer lists hold only sections with geometry in that layer, in visible order. The vanilla per-section checks still run on them.
 */
public final class BlazeVisibleSections {
    private static final ObjectArrayList<RenderSection> NONE=new ObjectArrayList<>(0);
    /** Moves when a section's contents or dirty mark change, on any thread, or when the visible list is refilled. */
    private static final AtomicLong CHANGES=new AtomicLong();
    private final ObjectArrayList<RenderSection> dirty=new ObjectArrayList<>();
    /** By chunk layer id: this is asked several thousand times a frame. */
    private Layer[] layers=new Layer[8];
    private final Layer withBlockEntities=new Layer();
    private ObjectArrayList<RenderSection> source;
    private int scanned;
    private boolean tables;
    private ObjectArrayList<RenderSection> dirtySource;
    private int dirtySize;
    private long dirtyAt=-1,scannedAt=-1;
    private boolean missingMesh;

    public static void changed(){CHANGES.incrementAndGet();}
    /** Read before the contents it guards, so a change racing the read moves it again. */
    public static long changes(){return CHANGES.get();}

    private static boolean reuse(){return BlazeOptimization.SECTION_SCAN_REUSE.on()&&BlazeOptimization.SECTION_BATCH.on();}

    public ObjectArrayList<RenderSection> dirty(ObjectArrayList<RenderSection> visible){
        long now=CHANGES.get();
        if(reuse()&&visible==dirtySource&&visible.size()==dirtySize&&now==dirtyAt)return dirty;
        dirty.clear();
        for(int index=0,size=visible.size();index<size;index++){
            RenderSection section=visible.get(index);
            if(section.isDirty())dirty.add(section);
        }
        dirtySource=visible;
        dirtySize=visible.size();
        dirtyAt=now;
        return dirty;
    }

    /** Runs after pending uploads, so sections finished this frame are already in their layers. */
    public void scan(ObjectArrayList<RenderSection> visible){
        boolean tables=BlazeOptimization.SECTION_TABLE.on()&&BlazeOptimization.SECTION_BATCH.on();
        long now=CHANGES.get();
        if(reuse()&&tables==this.tables&&now==scannedAt&&!missingMesh&&scanned(visible))return;
        missingMesh=false;
        this.tables=tables;
        for(Layer layer:layers)if(layer!=null)layer.cursor=0;
        withBlockEntities.cursor=0;
        for(int index=0,size=visible.size();index<size;index++){
            RenderSection section=visible.get(index);
            BlazeSectionBuffers flat=tables?(BlazeSectionBuffers)(Object)section:null;
            long contents=tables?flat.overhaul$contents():((BlazeCompiledContents)section.getCompiled()).overhaul$contents();
            if((contents&BlazeCompiledContents.BLOCK_ENTITIES)!=0)withBlockEntities.put(section);
            contents&=~(BlazeCompiledContents.BLOCK_ENTITIES|BlazeCompiledContents.KNOWN);
            if(contents==0)continue;
            for(;contents!=0;contents&=contents-1){
                int id=Long.numberOfTrailingZeros(contents);
                if(tables){
                    BlazeMesh mesh=flat.overhaul$mesh(id);
                    if(mesh==null)missingMesh=true;
                    layerOf(id).put(section,mesh,flat.overhaul$originX(),flat.overhaul$originY(),flat.overhaul$originZ());
                }
                else layerOf(id).put(section);
            }
        }
        for(Layer layer:layers)if(layer!=null)layer.sections.size(layer.cursor);
        withBlockEntities.sections.size(withBlockEntities.cursor);
        source=visible;
        scanned=visible.size();
        scannedAt=now;
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

    /** The scan's flat view of a layer, or null when it has none or its list no longer stands. */
    public Layer table(ObjectArrayList<RenderSection> visible,RenderType type){
        if(!tables||!scanned(visible))return null;
        int id=type.getChunkLayerId();
        return id<0||id>=layers.length||layers[id]==null?Layer.EMPTY:layers[id];
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
    public static final class Layer {
        private static final Layer EMPTY=new Layer();
        private final ObjectArrayList<RenderSection> sections=new ObjectArrayList<>();
        private int cursor;
        private BlazeMesh[] meshes=new BlazeMesh[256];
        private int[] origins=new int[3*256];
        private void put(RenderSection section){
            if(cursor==sections.size())sections.add(section);
            else if(sections.get(cursor)!=section)sections.set(cursor,section);
            cursor++;
        }
        private void put(RenderSection section,BlazeMesh mesh,int x,int y,int z){
            int at=cursor;
            put(section);
            if(at==meshes.length){
                meshes=Arrays.copyOf(meshes,at*2);
                origins=Arrays.copyOf(origins,at*6);
            }
            if(meshes[at]!=mesh)meshes[at]=mesh;
            origins[at*3]=x;
            origins[at*3+1]=y;
            origins[at*3+2]=z;
        }
        public int size(){return cursor;}
        public BlazeMesh mesh(int index){return meshes[index];}
        public int originX(int index){return origins[index*3];}
        public int originY(int index){return origins[index*3+1];}
        public int originZ(int index){return origins[index*3+2];}
    }
}
