package dev.overhaul.minecraft.terrain;

import com.mojang.blaze3d.vertex.VertexSorting;
import dev.overhaul.core.memory.DirectSlabPool;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Objects;

/**
 * Continuous terrain compilation through the game's normal Results/upload boundary.
 * Each call owns its tessellators, visibility and vertex writers. The supplied region
 * retains Minecraft's worker-compatible light/tint queries; it is not a frozen world.
 */
public final class OverhaulSectionCompiler implements AutoCloseable {
    private final boolean ambientOcclusion,cutoutLeaves;
    private final BlockStateModelSet blockModels;
    private final FluidStateModelSet fluidModels;
    private final BlockColors blockColors;
    private final DirectSlabPool pool;
    private final boolean ownsPool;
    private final TerrainCompilationMonitor monitor=new TerrainCompilationMonitor();
    private final Object lifecycle=new Object();
    private boolean closed;
    private int activeCompiles;

    public OverhaulSectionCompiler(boolean ambientOcclusion,boolean cutoutLeaves,
            BlockStateModelSet blockModels,FluidStateModelSet fluidModels,BlockColors blockColors) {
        this(ambientOcclusion,cutoutLeaves,blockModels,fluidModels,blockColors,new DirectSlabPool(64*1024,64),true);
    }
    public OverhaulSectionCompiler(boolean ambientOcclusion,boolean cutoutLeaves,
            BlockStateModelSet blockModels,FluidStateModelSet fluidModels,BlockColors blockColors,
            DirectSlabPool pool) {
        this(ambientOcclusion,cutoutLeaves,blockModels,fluidModels,blockColors,pool,false);
    }
    private OverhaulSectionCompiler(boolean ambientOcclusion,boolean cutoutLeaves,
            BlockStateModelSet blockModels,FluidStateModelSet fluidModels,BlockColors blockColors,
            DirectSlabPool pool,boolean ownsPool) {
        this.ambientOcclusion=ambientOcclusion; this.cutoutLeaves=cutoutLeaves;
        this.blockModels=Objects.requireNonNull(blockModels,"blockModels");
        this.fluidModels=Objects.requireNonNull(fluidModels,"fluidModels");
        this.blockColors=Objects.requireNonNull(blockColors,"blockColors");
        this.pool=Objects.requireNonNull(pool,"pool");
        this.ownsPool=ownsPool;
    }

    public SectionCompiler.Results compile(SectionPos section,RenderSectionRegion region,
            VertexSorting sorting,SectionBufferBuilderPack pack) {
        Objects.requireNonNull(section,"section"); Objects.requireNonNull(region,"region");
        Objects.requireNonNull(sorting,"sorting"); Objects.requireNonNull(pack,"pack");
        SectionCompiler.Results result=new SectionCompiler.Results();
        beginCompile();
        try {
            monitor.begin(section.asLong());
            BlockModelLighter.enableCaching();
            int models=0,liquids=0;
            try (LayerMeshBuilder layers=new LayerMeshBuilder(pack,pool)) {
                VisGraph visibility=new VisGraph();
                var renderer=new ModelBlockRenderer(ambientOcclusion,true,blockColors);
                var fluids=new FluidRenderer(fluidModels);
                var normalOutput=layers.blockOutput(false);
                var opaqueOutput=layers.blockOutput(true);
                var fluidOutput=layers.fluidOutput();
                BlockPos origin=section.origin();
                for (BlockPos position:BlockPos.betweenClosed(origin,origin.offset(15,15,15))) {
                    BlockState state=region.getBlockState(position);
                    if (state.isAir()) continue;
                    try {
                        if (state.isSolidRender()) visibility.setOpaque(position);
                        if (state.hasBlockEntity()) {
                            var entity=region.getBlockEntity(position);
                            if (entity!=null) result.blockEntities.add(entity);
                        }
                        var fluid=state.getFluidState();
                        if (!fluid.isEmpty()) {
                            fluids.tesselate(region,position,fluidOutput,state,fluid);
                            liquids++;
                        }
                        if (state.getRenderShape()==RenderShape.MODEL) {
                            var output=ModelBlockRenderer.forceOpaque(cutoutLeaves,state)?opaqueOutput:normalOutput;
                            renderer.tesselateBlock(output,SectionPos.sectionRelative(position.getX()),
                                    SectionPos.sectionRelative(position.getY()),SectionPos.sectionRelative(position.getZ()),
                                    region,position,state,blockModels.get(state),state.getSeed(position));
                            models++;
                        }
                    } catch (Throwable failure) { throw blockFailure(region,position,state,failure); }
                }
                layers.finish(result,sorting);
                result.visibilitySet=visibility.resolve();
            }
            recordCompletion(section.asLong(),result,models,liquids);
            return result;
        } catch (RuntimeException|Error failure) {
            monitor.failed(section.asLong(),failure);
            try { result.release(); } catch (RuntimeException|Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        } finally {
            try { BlockModelLighter.clearCache(); }
            finally { endCompile(); }
        }
    }

    private static ReportedException blockFailure(RenderSectionRegion region,BlockPos position,
            BlockState state,Throwable failure) {
        CrashReport report=CrashReport.forThrowable(failure,"Compiling Overhaul terrain block");
        var category=report.addCategory("Block being tessellated");
        CrashReportCategory.populateBlockDetails(category,region,position,state);
        return new ReportedException(report);
    }

    private void recordCompletion(long section,SectionCompiler.Results result,int models,int fluids) {
        long count=0,bytes=0;
        for (var mesh:result.renderedLayers.values()) {
            count+=mesh.drawState().vertexCount()/4;
            bytes+=mesh.vertexBuffer().remaining();
        }
        monitor.completed(section,models,fluids,bytes,count);
    }

    public TerrainCompilationMonitor monitor() { return monitor; }
    public int activeLeases() { return pool.activeLeases(); }
    public boolean isClosed() { synchronized(lifecycle){return closed;} }

    private void beginCompile() {
        synchronized (lifecycle) {
            if (closed) throw new IllegalStateException("Terrain compiler is closed");
            activeCompiles++;
        }
    }
    private void endCompile() {
        synchronized (lifecycle) {
            activeCompiles--;
            if (closed&&activeCompiles==0&&ownsPool) pool.close();
        }
    }
    /**
     * Dispatcher retires a generation only after every task that could have captured
     * it has returned. Already entered calls may finish without waiting on close.
     */
    @Override public void close() {
        synchronized (lifecycle) {
            closed=true;
            if (activeCompiles==0&&ownsPool) pool.close();
        }
    }
}
