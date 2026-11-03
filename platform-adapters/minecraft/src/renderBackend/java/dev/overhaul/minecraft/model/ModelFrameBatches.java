package dev.overhaul.minecraft.model;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import dev.overhaul.minecraft.shader.SceneDrawDomain;
import java.util.ArrayList;
import java.util.List;

/** Matches the game's prepared-state equality and consecutive/nonconsecutive consolidation rules. */
final class ModelFrameBatches {
    static final class Batch {
        final PreparedRenderType prepared;
        final VertexSorting sorting;
        final SceneDrawDomain domain;
        final IntArrayList records=new IntArrayList();
        Batch(PreparedRenderType prepared,VertexSorting sorting){this(prepared,sorting,SceneDrawDomain.UNKNOWN);}
        Batch(PreparedRenderType prepared,VertexSorting sorting,SceneDrawDomain domain){this.prepared=prepared;this.sorting=sorting;this.domain=domain;}
    }
    private final boolean canReorder;
    private final List<Batch> batches=new ArrayList<>();
    private RenderType lastType;
    private Batch last;
    private SceneDrawDomain lastDomain;

    ModelFrameBatches(boolean sorted){canReorder=!sorted;}
    Batch batch(RenderType type){
        return batch(type,SceneDrawDomain.UNKNOWN);
    }
    Batch batch(RenderType type,SceneDrawDomain domain){
        if(last==null||lastType!=type||lastDomain!=domain||!type.canConsolidateConsecutiveGeometry()){
            PreparedRenderType prepared=type.prepare();last=null;
            if(canReorder&&type.canConsolidateConsecutiveGeometry()){
                for(Batch batch:batches)if(batch.domain==domain&&batch.prepared.equals(prepared)){last=batch;break;}
            }
            if(last==null){
                boolean improved=Minecraft.getInstance().gameRenderer.useImprovedTransparency();
                VertexSorting sorting=type.sortOnUpload()&&!improved?RenderSystem.getProjectionType().vertexSorting():null;
                last=new Batch(prepared,sorting,domain);batches.add(last);
            }
            lastType=type;
            lastDomain=domain;
        }
        return last;
    }
    List<Batch> batches(){return batches;}
}
