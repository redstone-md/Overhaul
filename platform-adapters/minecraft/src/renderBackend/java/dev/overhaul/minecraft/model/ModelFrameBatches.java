package dev.overhaul.minecraft.model;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import java.util.ArrayList;
import java.util.List;

/** Matches the game's prepared-state equality and consecutive/nonconsecutive consolidation rules. */
final class ModelFrameBatches {
    static final class Batch {
        final PreparedRenderType prepared;
        final VertexSorting sorting;
        final IntArrayList records=new IntArrayList();
        Batch(PreparedRenderType prepared,VertexSorting sorting){this.prepared=prepared;this.sorting=sorting;}
    }
    private final boolean canReorder;
    private final List<Batch> batches=new ArrayList<>();
    private RenderType lastType;
    private Batch last;

    ModelFrameBatches(boolean sorted){canReorder=!sorted;}
    Batch batch(RenderType type){
        if(last==null||lastType!=type||!type.canConsolidateConsecutiveGeometry()){
            PreparedRenderType prepared=type.prepare();last=null;
            if(canReorder&&type.canConsolidateConsecutiveGeometry()){
                for(Batch batch:batches)if(batch.prepared.equals(prepared)){last=batch;break;}
            }
            if(last==null){
                boolean improved=Minecraft.getInstance().gameRenderer.useImprovedTransparency();
                VertexSorting sorting=type.sortOnUpload()&&!improved?RenderSystem.getProjectionType().vertexSorting():null;
                last=new Batch(prepared,sorting);batches.add(last);
            }
            lastType=type;
        }
        return last;
    }
    List<Batch> batches(){return batches;}
}
