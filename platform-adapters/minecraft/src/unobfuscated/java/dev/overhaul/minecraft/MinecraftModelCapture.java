package dev.overhaul.minecraft;

import com.mojang.blaze3d.vertex.QuadInstance;
import dev.overhaul.adapter.SectionKey;
import dev.overhaul.core.model.ModelQuad;
import dev.overhaul.core.model.ModelSnapshot;
import dev.overhaul.core.model.ModelVertex;
import dev.overhaul.core.model.RenderLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures actual 26.3 baked geometry, tint/AO and light on the world owner.
 * This initial extraction uses the game's tessellator; it is not the eventual
 * off-thread replacement for that tessellator. Workers receive only its immutable
 * result. Fluids and separately rendered block entities require distinct paths.
 */
public final class MinecraftModelCapture {
    private static final int MAX_QUADS = 262_144;
    private final Minecraft minecraft;
    private final Thread owner = Thread.currentThread();

    public MinecraftModelCapture(Minecraft minecraft) { this.minecraft = minecraft; }

    public Capture capture(SectionKey section, long revision) {
        if (Thread.currentThread() != owner || !minecraft.isSameThread()) {
            throw new IllegalStateException("Live model capture belongs to the client world thread");
        }
        ClientLevel level = minecraft.level;
        if (level == null) throw new IllegalStateException("No client level is loaded");
        for (int dz=-1; dz<=1; dz++) for (int dx=-1; dx<=1; dx++) {
            if (!level.hasChunk(Math.addExact(section.x(),dx),Math.addExact(section.z(),dz))) {
                throw new IllegalStateException("Model capture must not load missing neighbor chunks");
            }
        }
        int ox=Math.multiplyExact(section.x(),16), oy=Math.multiplyExact(section.y(),16);
        int oz=Math.multiplyExact(section.z(),16);
        ModelBlockRenderer renderer=new ModelBlockRenderer(minecraft.options.ambientOcclusion().get(),true,minecraft.getBlockColors());
        var models=minecraft.getModelManager().getBlockStateModelSet();
        List<ModelQuad> quads=new ArrayList<>();
        Output output=new Output(quads);
        BlockPos.MutableBlockPos position=new BlockPos.MutableBlockPos();
        int blocks=0, fluids=0;
        for (int y=0; y<16; y++) for (int z=0; z<16; z++) for (int x=0; x<16; x++) {
            position.set(Math.addExact(ox,x),Math.addExact(oy,y),Math.addExact(oz,z));
            var state=level.getBlockState(position);
            if (!state.getFluidState().isEmpty()) fluids++;
            if (state.getRenderShape()!=RenderShape.MODEL) continue;
            output.material=Block.getId(state);
            renderer.tesselateBlock(output,x,y,z,level,position,state,models.get(state),state.getSeed(position));
            blocks++;
        }
        return new Capture(new ModelSnapshot(revision,ox,oy,oz,quads),blocks,fluids,
                output.opaque,output.cutout,output.translucent,output.nonCube);
    }

    public record Capture(ModelSnapshot snapshot,int modelBlocks,int fluidBlocks,
                          int opaqueQuads,int cutoutQuads,int translucentQuads,int nonCubeQuads) {}

    private static final class Output implements BlockQuadOutput {
        private final List<ModelQuad> quads;
        private int material, opaque, cutout, translucent, nonCube;
        private Output(List<ModelQuad> quads) { this.quads=quads; }

        @Override public void put(float x,float y,float z,BakedQuad quad,QuadInstance instance) {
            if (quads.size()>=MAX_QUADS) throw new IllegalStateException("Section model snapshot exceeds its quad budget");
            RenderLayer layer=layer(quad.materialInfo().layer());
            ModelVertex[] vertices=new ModelVertex[4];
            Vector3fc normal=quad.direction().getUnitVec3f();
            boolean fractional=false;
            for (int i=0; i<4; i++) {
                Vector3fc p=quad.position(i);
                fractional|=fraction(p.x()+x)||fraction(p.y()+y)||fraction(p.z()+z);
                long uv=quad.packedUV(i);
                vertices[i]=new ModelVertex(p.x()+x,p.y()+y,p.z()+z,UVPair.unpackU(uv),UVPair.unpackV(uv),
                        ModelVertex.rgbaFromArgb(instance.getColor(i)),
                        instance.getLightCoordsWithEmission(i,quad.materialInfo().lightEmission()),
                        normal.x(),normal.y(),normal.z());
            }
            quads.add(new ModelQuad(layer,material,layer==RenderLayer.CUTOUT ? .5f : 0f,vertices));
            if (layer==RenderLayer.OPAQUE) opaque++;
            else if (layer==RenderLayer.CUTOUT) cutout++;
            else translucent++;
            if (fractional) nonCube++;
        }

        private static boolean fraction(float value) { return value!=(float)Math.rint(value); }
        private static RenderLayer layer(ChunkSectionLayer layer) {
            if (layer==ChunkSectionLayer.SOLID) return RenderLayer.OPAQUE;
            if (layer==ChunkSectionLayer.CUTOUT) return RenderLayer.CUTOUT;
            if (layer==ChunkSectionLayer.TRANSLUCENT) return RenderLayer.TRANSLUCENT;
            throw new IllegalArgumentException("Unknown terrain render layer: "+layer);
        }
    }
}
