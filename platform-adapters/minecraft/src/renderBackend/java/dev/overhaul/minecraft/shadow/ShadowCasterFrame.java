package dev.overhaul.minecraft.shadow;

import dev.overhaul.shader.pack.PackShadowSettings;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Separate owner-thread caster state; no main frustum/occlusion list is edited. */
public final class ShadowCasterFrame {
    private final ObjectArrayList<SectionRenderDispatcher.RenderSection> sections=new ObjectArrayList<>();
    private final ObjectArrayList<SectionRenderDispatcher.RenderSection> updates=new ObjectArrayList<>();
    private final Set<SectionRenderDispatcher.RenderSection> unique=Collections.newSetFromMap(new IdentityHashMap<>());
    private final LongOpenHashSet chunks=new LongOpenHashSet();
    private final Set<BlockEntity> blockEntities=Collections.newSetFromMap(new IdentityHashMap<>());
    private final IdentityHashMap<Object,AABB> bounds=new IdentityHashMap<>();
    private final LevelRenderState states=new LevelRenderState();
    private Vec3 origin=Vec3.ZERO;
    private double radiusSquared;
    private boolean selected,extracted;

    public ObjectArrayList<SectionRenderDispatcher.RenderSection> select(ViewArea area,
            ObjectArrayList<SectionRenderDispatcher.RenderSection> visible,PackShadowSettings settings,boolean requested,Vec3 camera){
        reset();if(area==null||!requested)return visible;
        selected=true;origin=camera;
        radiusSquared=radiusSquared(settings);
        ((ShadowViewAreaAccess)area).overhaul$forEachSection(section->{if(inRange(section.getBoundingBox()))sections.add(section);});
        updates.addAll(visible);unique.addAll(visible);
        for(var section:sections)if(unique.add(section))updates.add(section);
        unique.clear();return updates;
    }

    public void extract(ClientLevel level,LevelRenderer renderer,DeltaTracker delta,LevelRenderState main){
        if(!selected||level==null)return;
        states.cameraRenderState=main.cameraRenderState;states.worldPartialTicks=main.worldPartialTicks;states.gameTime=main.gameTime;
        var entities=renderer.entityRenderDispatcher();var rate=level.tickRateManager();
        for(var entity:level.entitiesForRendering()){
            if(entity.isRemoved()||!inRange(entity.getBoundingBox()))continue;
            // New off-camera entities need the same interpolation initialization as ordinary extraction.
            if(entity.tickCount==0){entity.xOld=entity.getX();entity.yOld=entity.getY();entity.zOld=entity.getZ();}
            float partial=delta.getGameTimeDeltaPartialTick(!rate.isEntityFrozen(entity));
            var state=entities.extractEntity(entity,partial);states.entityRenderStates.add(state);bounds.put(state,entity.getBoundingBox());
        }
        for(var section:sections){
            long node=section.getSectionNode();int x=SectionPos.x(node),z=SectionPos.z(node);
            if(!chunks.add(ChunkPos.asLong(x,z)))continue;
            var chunk=level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false);if(chunk==null)continue;
            for(var entity:chunk.getBlockEntities().values())extractBlock(entity,renderer,main.worldPartialTicks);
        }
        for(var entity:level.getGloballyRenderedBlockEntities())extractBlock(entity,renderer,main.worldPartialTicks);
        chunks.clear();blockEntities.clear();extracted=true;
    }
    private void extractBlock(BlockEntity entity,LevelRenderer renderer,float partial){
        if(!blockEntities.add(entity)||entity.isRemoved())return;var box=new AABB(entity.getBlockPos());if(!inRange(box))return;
        var blocks=renderer.blockEntityRenderDispatcher();var blockRenderer=blocks.getRenderer(entity);if(blockRenderer==null)return;
        var state=blocks.tryExtractRenderState(entity,partial,null,blockRenderer.shouldRenderOffScreen());
        if(state!=null){states.blockEntityRenderStates.add(state);bounds.put(state,box);}
    }
    /** Unknown/new configurations were extracted unbounded; apply only the actual installed generation's admission. */
    public void restrict(PackShadowSettings settings){
        double next=radiusSquared(settings);if(next>=radiusSquared)return;radiusSquared=next;
        sections.removeIf(section->!inRange(section.getBoundingBox()));
        states.entityRenderStates.removeIf(state->!inRange(bounds.get(state)));
        states.blockEntityRenderStates.removeIf(state->!inRange(bounds.get(state)));
    }
    public boolean ready(){return extracted;}
    public LevelRenderState states(){return states;}
    public ObjectArrayList<SectionRenderDispatcher.RenderSection> sections(){return sections;}
    public void reset(){
        sections.clear();updates.clear();unique.clear();chunks.clear();blockEntities.clear();bounds.clear();states.entityRenderStates.clear();states.blockEntityRenderStates.clear();
        states.cameraRenderState=null;selected=extracted=false;
    }
    private boolean inRange(AABB box){
        double x=distance(origin.x,box.minX,box.maxX),y=distance(origin.y,box.minY,box.maxY),z=distance(origin.z,box.minZ,box.maxZ);
        return x*x+y*y+z*z<=radiusSquared;
    }
    private static double distance(double value,double min,double max){return value<min?min-value:value>max?value-max:0;}
    private static double radiusSquared(PackShadowSettings settings){
        if(settings==null||settings.renderMultiplier()<=0)return Double.POSITIVE_INFINITY;
        double radius=(double)settings.distance()*settings.renderMultiplier();return radius*radius;
    }
}
