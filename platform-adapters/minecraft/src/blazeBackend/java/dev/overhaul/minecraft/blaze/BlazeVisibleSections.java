package dev.overhaul.minecraft.blaze;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.renderer.RenderType;

/**
 * Per-frame views of the visible section list, so its long scans run once instead of once per layer.
 * Layer lists hold only sections with geometry in that layer, in visible order. The vanilla per-section checks still run on them.
 * Each game version names its list entries differently; a subclass says how to read one.
 */
public abstract class BlazeVisibleSections<T> {
    private static final ObjectArrayList<Object> NONE=new ObjectArrayList<>(0);
    private final ObjectArrayList<T> dirty=new ObjectArrayList<>();
    /** By chunk layer id: this is asked several thousand times a frame. */
    private Layer[] layers=new Layer[8];
    private final Layer withBlockEntities=new Layer();
    private ObjectArrayList<T> source;
    private int scanned,rendered;
    private boolean tables;
    private ObjectArrayList<T> dirtySource;
    private int dirtySize;
    private long dirtyAt=-1,scannedAt=-1;
    private boolean missingMesh;

    public static void changed(){BlazeSectionChanges.changed();}
    public static long changes(){return BlazeSectionChanges.changes();}

    protected abstract boolean isDirty(T entry);
    /** The entry's section, with its flat layer data. */
    protected abstract BlazeSectionBuffers buffers(T entry);
    /** The entry's layers as its compile reads now, for when no flat tables are kept. */
    protected abstract long compiledContents(T entry);

    private static boolean reuse(){return BlazeOptimization.SECTION_SCAN_REUSE.on()&&BlazeOptimization.SECTION_BATCH.on();}

    public ObjectArrayList<T> dirty(ObjectArrayList<T> visible){
        long now=BlazeSectionChanges.changes();
        if(reuse()&&visible==dirtySource&&visible.size()==dirtySize&&now==dirtyAt)return dirty;
        dirty.clear();
        for(int index=0,size=visible.size();index<size;index++){
            T entry=visible.get(index);
            if(isDirty(entry))dirty.add(entry);
        }
        dirtySource=visible;
        dirtySize=visible.size();
        dirtyAt=now;
        return dirty;
    }

    /** Runs after pending uploads, so sections finished this frame are already in their layers. */
    public void scan(ObjectArrayList<T> visible){
        boolean tables=BlazeOptimization.SECTION_TABLE.on()&&BlazeOptimization.SECTION_BATCH.on();
        long now=BlazeSectionChanges.changes();
        if(reuse()&&tables==this.tables&&now==scannedAt&&!missingMesh&&scanned(visible))return;
        missingMesh=false;
        Layer.dropStale();
        this.tables=tables;
        for(Layer layer:layers)if(layer!=null)layer.cursor=0;
        withBlockEntities.cursor=0;
        int rendered=0;
        for(int index=0,size=visible.size();index<size;index++){
            T entry=visible.get(index);
            BlazeSectionBuffers flat=tables?buffers(entry):null;
            long contents=tables?flat.overhaul$contents():compiledContents(entry);
            if((contents&BlazeCompiledContents.BLOCK_ENTITIES)!=0)withBlockEntities.put(entry);
            contents&=~(BlazeCompiledContents.BLOCK_ENTITIES|BlazeCompiledContents.KNOWN);
            if(contents==0)continue;
            rendered++;
            for(;contents!=0;contents&=contents-1){
                int id=Long.numberOfTrailingZeros(contents);
                if(tables){
                    Layer layer=layerOf(id);
                    int x=flat.overhaul$originX(),y=flat.overhaul$originY(),z=flat.overhaul$originZ();
                    if(layer.putKept(entry,x,y,z))continue;
                    BlazeMesh mesh=flat.overhaul$mesh(id);
                    if(mesh==null)missingMesh=true;
                    layer.put(entry,mesh,x,y,z);
                }
                else layerOf(id).put(entry);
            }
        }
        for(Layer layer:layers)if(layer!=null)layer.sections.size(layer.cursor);
        withBlockEntities.sections.size(withBlockEntities.cursor);
        source=visible;
        scanned=visible.size();
        this.rendered=rendered;
        scannedAt=now;
    }

    /** A list swapped or changed since the scan is walked whole, as vanilla would. */
    @SuppressWarnings("unchecked")
    public ObjectArrayList<T> layer(ObjectArrayList<T> visible,RenderType type){
        if(!scanned(visible))return visible;
        int id=BlazeChunkLayers.id(type);
        Layer layer=id<0||id>=layers.length?null:layers[id];
        return (ObjectArrayList<T>)(layer==null?NONE:layer.sections);
    }

    /** A section compiled after the scan shows its block entities a frame later, as its mesh does. */
    @SuppressWarnings("unchecked")
    public ObjectArrayList<T> blockEntitySections(ObjectArrayList<T> visible){
        return scanned(visible)?(ObjectArrayList<T>)withBlockEntities.sections:visible;
    }

    /** The scan's flat view of a layer, or null when it has none or its list no longer stands. */
    public Layer table(ObjectArrayList<T> visible,RenderType type){
        if(!tables||!scanned(visible))return null;
        int id=BlazeChunkLayers.id(type);
        return id<0||id>=layers.length||layers[id]==null?Layer.EMPTY:layers[id];
    }

    /** Sections with geometry in any layer, as the debug screen counts them every frame; -1 when the list no longer stands. */
    public int rendered(ObjectArrayList<T> visible){
        return scanned(visible)?rendered:-1;
    }

    private Layer layerOf(int id){
        if(id>=layers.length)layers=Arrays.copyOf(layers,id+1);
        Layer layer=layers[id];
        if(layer==null)layers[id]=layer=new Layer();
        return layer;
    }

    /** Whether {@link #layer} would answer from a scan, so its sections are known to have geometry in the layer asked about. */
    public boolean scanned(ObjectArrayList<T> visible){
        return visible==source&&visible.size()==scanned;
    }

    /**
     * Rewrites only changed slots: the order rarely changes between frames, and each reference store pays a GC barrier.
     * With {@code sectionDraws} each slot also copies its mesh's draw fields, so drawing reads flat arrays instead of
     * one scattered mesh per section; a mesh copied here marks itself, and any later change to it makes the scan stale.
     */
    public static final class Layer extends BlazeDrawTable {
        private static final Layer EMPTY=new Layer();
        private static final boolean DRAWS=BlazeOptimization.SECTION_DRAWS.on();
        private static final boolean KEEP=BlazeOptimization.SECTION_SLOT_KEEP.on();
        private static final ConcurrentLinkedQueue<BlazeMesh> STALE=new ConcurrentLinkedQueue<>();
        private final ObjectArrayList<Object> sections=new ObjectArrayList<>();
        private int cursor;
        private int[] origins=new int[3*256];
        private void put(Object section){
            if(cursor==sections.size())sections.add(section);
            else if(sections.get(cursor)!=section)sections.set(cursor,section);
            cursor++;
        }
        private void put(Object section,BlazeMesh mesh,int x,int y,int z){
            int at=cursor;
            put(section);
            put(at,mesh,DRAWS);
            if(KEEP&&mesh!=null){mesh.slotOwner=this;mesh.slotAt=at;}
            origin(at,x,y,z);
        }
        /**
         * Keeps a slot that already holds this section and its mesh's copy, so the scan reads neither the section's mesh
         * list nor the mesh: a mesh's layer never changes, and a changed mesh dropped its copy through {@link #stale}.
         */
        private boolean putKept(Object section,int x,int y,int z){
            int at=cursor;
            if(!KEEP||at>=sections.size()||sections.get(at)!=section||at>=capacity()||mesh(at)==null)return false;
            cursor++;
            origin(at,x,y,z);
            return true;
        }
        private void origin(int at,int x,int y,int z){
            origins[at*3]=x;
            origins[at*3+1]=y;
            origins[at*3+2]=z;
        }
        /** Called by a copied mesh after its draw fields change, from any thread; the next scan drops that copy. */
        static void stale(BlazeMesh mesh){
            if(!KEEP||mesh.slotQueued)return;
            mesh.slotQueued=true;
            STALE.add(mesh);
        }
        /** Runs on the render thread before a scan, so every dropped slot is filled again from its mesh. */
        private static void dropStale(){
            for(BlazeMesh mesh;(mesh=STALE.poll())!=null;){
                mesh.slotQueued=false;
                Layer owner=mesh.slotOwner;
                if(owner!=null)owner.forget(mesh.slotAt,mesh);
            }
        }
        @Override void grow(int slots){
            super.grow(slots);
            origins=Arrays.copyOf(origins,slots*3);
        }
        public int size(){return cursor;}
        /** Whether each slot holds its mesh's draw fields, so a batch can add it without reading the mesh. */
        public boolean draws(){return DRAWS;}
        public int originX(int index){return origins[index*3];}
        public int originY(int index){return origins[index*3+1];}
        public int originZ(int index){return origins[index*3+2];}
    }
}
