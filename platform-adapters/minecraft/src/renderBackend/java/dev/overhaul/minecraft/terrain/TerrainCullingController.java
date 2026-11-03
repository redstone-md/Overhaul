package dev.overhaul.minecraft.terrain;

import dev.overhaul.core.culling.ConservativeProjection;
import dev.overhaul.core.culling.SoftwareDepthBuffer;
import dev.overhaul.core.culling.TerrainGeometry;
import dev.overhaul.core.culling.PerspectiveEye;
import dev.overhaul.minecraft.shader.ShaderResourceGate;
import dev.overhaul.core.culling.ProjectionPrecision;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Util;
import org.joml.Matrix4fc;
import java.util.*;

/** Owner-thread frame transaction; terrain draw inputs retain their exact snapshotted mesh identities. */
public final class TerrainCullingController {
    private static final int MAX_SECTIONS=8192;
    private static final int MAX_FACE_ATTEMPTS=8192,MAX_FACES_PER_FRAME=256;
    private static final class Watch {
        long frame,lastTestedFrame,lastCulledFrame,lastVisibleFrame,culledCount,visibleCount,lastMeshStart;
        boolean lastCulled,lastEligible,coherent;
    }
    private static final class Entry {
        final SectionRenderDispatcher.RenderSection section;
        final long node;
        final SectionMesh mesh;
        final TerrainGeometry geometry;
        final double x,y,z,distance;
        boolean hidden,tested;
        Entry(SectionRenderDispatcher.RenderSection section,double cx,double cy,double cz) {
            this.section=section;node=section.getSectionNode();mesh=section.getSectionMesh();
            geometry=mesh instanceof TerrainGeometryAccess access?access.overhaul$geometry():null;
            var origin=section.getRenderOrigin();x=origin.getX()-cx;y=origin.getY()-cy;z=origin.getZ()-cz;
            distance=(x+8)*(x+8)+(y+8)*(y+8)+(z+8)*(z+8);
        }
    }
    private final Thread owner=Thread.currentThread();
    private final ShaderResourceGate shaders=new ShaderResourceGate("/overhaul-terrain-shaders.properties");
    private final SoftwareDepthBuffer depth=new SoftwareDepthBuffer(160,90);
    private final IdentityHashMap<SectionRenderDispatcher.RenderSection,Entry> frame=new IdentityHashMap<>();
    private final Set<Long> previouslyHidden=new HashSet<>();
    private final Map<Long,Watch> watches=new LinkedHashMap<>();
    private final double[] quad=new double[12];
    private final float[] projection=new float[16],rotation=new float[16];
    private double cameraX,cameraY,cameraZ;
    private long projectionSerial,preparedSerial,frames,tested,culled,rasterized,restored,maxCulled;
    private long lastTested,lastCulled,lastRasterized,lastFaceAttempts,budgetExhaustedFrames;
    private long precisionRejectedBounds,precisionRejectedFaces;
    private boolean prepared,eligible;
    private String eligibilityReason="projection missing";
    private ConservativeProjection projector;
    private PerspectiveEye effectiveEye;
    private ProjectionPrecision precision;

    public void projection(Matrix4fc matrix){requireOwner();matrix.get(projection);projectionSerial++;}
    public void prepare(Matrix4fc view,CameraRenderState camera) {
        requireOwner();clearFrame();prepared=false;eligible=false;effectiveEye=null;
        if(camera==null||camera.pos==null||!camera.initialized||projectionSerial==preparedSerial)return;
        preparedSerial=projectionSerial;view.get(rotation);
        double[] combined=new double[16];
        for(int column=0;column<4;column++)for(int row=0;row<4;row++)for(int k=0;k<4;k++)
            combined[column*4+row]+=(double)projection[k*4+row]*rotation[column*4+k];
        cameraX=camera.pos.x;cameraY=camera.pos.y;cameraZ=camera.pos.z;
        if(!Double.isFinite(cameraX)||!Double.isFinite(cameraY)||!Double.isFinite(cameraZ)||!properHandedness(combined))return;
        effectiveEye=PerspectiveEye.fromMatrix(combined);
        if(effectiveEye==null)return;
        try {
            projector=new ConservativeProjection(combined,depth.width(),depth.height(),
                    ConservativeProjection.ClipDepth.VULKAN_ZERO_TO_ONE,true);
            precision=new ProjectionPrecision(projection,rotation,depth.width(),depth.height(),
                    ConservativeProjection.ClipDepth.VULKAN_ZERO_TO_ONE,true);
            prepared=true;
        }catch(IllegalArgumentException uncertain){projector=null;}
    }
    /** Called only at the scoped visibleSections field read, after vanilla has acquired copyLock. */
    public ObjectArrayList<SectionRenderDispatcher.RenderSection> filter(
            ObjectArrayList<SectionRenderDispatcher.RenderSection> source,SectionRenderDispatcher dispatcher,double fadeSeconds) {
        requireOwner();lastTested=lastCulled=lastRasterized=lastFaceAttempts=0;frames++;
        var minecraft=Minecraft.getInstance();
        eligible=prepared&&source.size()<=MAX_SECTIONS&&shaders.eligible(minecraft.getResourceManager());
        eligibilityReason=!prepared?"uncertain or reflected projection":source.size()>MAX_SECTIONS?"section budget":shaders.reason();
        if(!eligible){
            recordRestored(Set.of());
            for(var section:source)observe(section.getSectionNode(),null,false,false,false);
            return source;
        }
        depth.clear();
        var entries=new ArrayList<Entry>(source.size());
        for(var section:source){var entry=new Entry(section,cameraX,cameraY,cameraZ);frame.put(section,entry);entries.add(entry);}
        entries.sort(Comparator.comparingDouble(entry->entry.distance));
        long now=Util.getMillis(),fadeMillis=Util.toMillis(fadeSeconds);
        Set<Long> hidden=new HashSet<>();
        for(var entry:entries) {
            var geometry=entry.geometry;
            if(geometry==null||!geometry.hasBounds())continue;
            // Keep the shader's integer chunk-minus-camera offset exactly representable in float32.
            if(Math.max(Math.abs(entry.x),Math.max(Math.abs(entry.y),Math.abs(entry.z)))>999_000)continue;
            if(hidden(entry)){entry.hidden=true;hidden.add(entry.node);lastCulled++;continue;}
            if(!(entry.mesh instanceof CompiledSectionMesh compiled)||!compiled.isVertexBufferUploaded(ChunkSectionLayer.SOLID)
                    ||compiled.getSectionDraw(ChunkSectionLayer.SOLID)==null||entry.section.getVisibility(now,fadeMillis)<1)continue;
            var draw=compiled.getSectionDraw(ChunkSectionLayer.SOLID);
            var allocation=dispatcher.getRenderSectionSlice(entry.mesh,ChunkSectionLayer.SOLID);
            if(allocation==null||allocation.vertexBuffer()==null||draw.hasCustomIndexBuffer()
                    &&(!compiled.isIndexBufferUploaded(ChunkSectionLayer.SOLID)||allocation.indexBuffer()==null))continue;
            for(int face=0;face<geometry.faceCount()&&lastFaceAttempts<MAX_FACE_ATTEMPTS&&lastRasterized<MAX_FACES_PER_FRAME;face++) {
                lastFaceAttempts++;
                int axis=geometry.faceAxis(face);
                double origin=axis==0?entry.x:axis==1?entry.y:entry.z;
                // Bob/portal transforms can move the effective perspective eye away from the raw camera origin.
                if(!effectiveEye.faces(axis,geometry.faceSign(face),origin+geometry.facePlane(face)))continue;
                geometry.face(face,entry.x,entry.y,entry.z,quad);
                if(!precision.safeQuad(quad,0)){precisionRejectedFaces++;continue;}
                if(projector.rasterizeQuad(depth,quad,0,precision.occluderDepth()))lastRasterized++;
            }
        }
        var filtered=new ObjectArrayList<SectionRenderDispatcher.RenderSection>(source.size());
        for(var section:source){var entry=frame.get(section);observe(entry.node,entry.mesh,entry.tested,entry.hidden,true);if(!entry.hidden)filtered.add(section);}
        tested+=lastTested;culled+=lastCulled;rasterized+=lastRasterized;maxCulled=Math.max(maxCulled,lastCulled);
        if(lastFaceAttempts>=MAX_FACE_ATTEMPTS||lastRasterized>=MAX_FACES_PER_FRAME)budgetExhaustedFrames++;
        recordRestored(hidden);return filtered;
    }
    private boolean hidden(Entry entry) {
        var geometry=entry.geometry;
        double minX=entry.x+geometry.min(0),minY=entry.y+geometry.min(1),minZ=entry.z+geometry.min(2);
        double maxX=entry.x+geometry.max(0),maxY=entry.y+geometry.max(1),maxZ=entry.z+geometry.max(2);
        if(!precision.safeBounds(minX,minY,minZ,maxX,maxY,maxZ)){precisionRejectedBounds++;return false;}
        var bounds=projector.projectBounds(minX,minY,minZ,maxX,maxY,maxZ);
        if(bounds==null)return false;
        entry.tested=true;lastTested++;
        float nearest=Math.max(0,Math.nextDown((float)(bounds.nearestDepth()-precision.depthError())));
        return depth.occluded(Math.max(0,bounds.minX()-1),Math.max(0,bounds.minY()-1),
                Math.min(depth.width(),bounds.maxX()+1),Math.min(depth.height(),bounds.maxY()+1),nearest);
    }
    /** Only extractSectionDrawGroups uses this; other game visibility and metadata queries remain ordinary. */
    public SectionMesh mesh(SectionRenderDispatcher.RenderSection section,SectionMesh current) {
        requireOwner();var entry=frame.get(section);
        return entry!=null&&entry.node==section.getSectionNode()?entry.mesh:current;
    }
    private void recordRestored(Set<Long> hidden){for(long previous:previouslyHidden)if(!hidden.contains(previous))restored++;previouslyHidden.clear();previouslyHidden.addAll(hidden);}
    public void clearFrame(){requireOwner();frame.clear();}
    public void reset(){requireOwner();clearFrame();previouslyHidden.clear();prepared=false;preparedSerial=projectionSerial;}
    public long reloadStarted(){requireOwner();reset();return shaders.reloadStarted();}
    public void reloadFinished(long generation,boolean success){requireOwner();shaders.reloadFinished(generation,success);reset();}
    public void watch(long section){requireOwner();if(watches.containsKey(section))return;if(watches.size()==8)throw new IllegalStateException("Terrain culling watch limit reached");watches.put(section,new Watch());}
    private void observe(long section,SectionMesh mesh,boolean tested,boolean hidden,boolean coherent){
        Watch watch=watches.get(section);if(watch==null)return;
        watch.frame=frames;watch.lastCulled=hidden;watch.lastEligible=eligible;watch.coherent=coherent;
        watch.lastMeshStart=mesh==null?0:mesh.getCompileTaskStartTime();
        if(tested)watch.lastTestedFrame=frames;
        if(hidden){watch.lastCulledFrame=frames;watch.culledCount++;}else{watch.lastVisibleFrame=frames;watch.visibleCount++;}
    }
    public Map<String,Object> observation(long section){
        requireOwner();Watch watch=watches.get(section);if(watch==null)throw new IllegalStateException("Register a culling watch first");
        var result=new LinkedHashMap<String,Object>();result.put("frame",watch.frame);result.put("lastTestedFrame",watch.lastTestedFrame);
        result.put("lastCulledFrame",watch.lastCulledFrame);result.put("lastVisibleFrame",watch.lastVisibleFrame);
        result.put("culledCount",watch.culledCount);result.put("visibleCount",watch.visibleCount);result.put("lastMeshStart",watch.lastMeshStart);
        result.put("lastCulled",watch.lastCulled);result.put("eligible",watch.lastEligible);result.put("coherent",watch.coherent);return Map.copyOf(result);
    }
    public Map<String,Object> snapshot() {
        requireOwner();var result=new LinkedHashMap<String,Object>();
        result.put("frames",frames);result.put("tested",tested);result.put("culled",culled);result.put("rasterized",rasterized);
        result.put("restored",restored);result.put("maxCulled",maxCulled);result.put("lastTested",lastTested);
        result.put("lastCulled",lastCulled);result.put("lastRasterized",lastRasterized);result.put("eligible",eligible);
        result.put("lastFaceAttempts",lastFaceAttempts);result.put("faceAttemptBudget",MAX_FACE_ATTEMPTS);result.put("rasterizedFaceBudget",MAX_FACES_PER_FRAME);
        result.put("budgetExhaustedFrames",budgetExhaustedFrames);result.put("eligibilityReason",eligibilityReason);
        result.put("precisionRejectedBounds",precisionRejectedBounds);result.put("precisionRejectedFaces",precisionRejectedFaces);
        result.put("shaderEligibility",shaders.reason());result.put("resourceGeneration",shaders.generation());
        result.put("depthWidth",depth.width());result.put("depthHeight",depth.height());return Map.copyOf(result);
    }
    /** Vanilla's reversed-Z perspective has positive determinant; reflections reverse back-face semantics. */
    private static boolean properHandedness(double[] m){
        double norm=0;for(double value:m){if(!Double.isFinite(value))return false;norm=Math.max(norm,Math.abs(value));}
        double a0=m[0]*m[5]-m[1]*m[4],a1=m[0]*m[6]-m[2]*m[4],a2=m[0]*m[7]-m[3]*m[4];
        double a3=m[1]*m[6]-m[2]*m[5],a4=m[1]*m[7]-m[3]*m[5],a5=m[2]*m[7]-m[3]*m[6];
        double b0=m[8]*m[13]-m[9]*m[12],b1=m[8]*m[14]-m[10]*m[12],b2=m[8]*m[15]-m[11]*m[12];
        double b3=m[9]*m[14]-m[10]*m[13],b4=m[9]*m[15]-m[11]*m[13],b5=m[10]*m[15]-m[11]*m[14];
        double determinant=a0*b5-a1*b4+a2*b3+a3*b2-a4*b1+a5*b0;
        return Double.isFinite(determinant)&&determinant>1e-12*norm*norm*norm*norm;
    }
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Terrain culling belongs to the render owner");}
}
