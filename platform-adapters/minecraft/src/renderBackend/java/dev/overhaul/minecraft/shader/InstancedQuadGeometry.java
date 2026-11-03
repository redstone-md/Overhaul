package dev.overhaul.minecraft.shader;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.IndexType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Renderer-owned four corners and six indices; instance payloads belong to the submitted frame. */
public final class InstancedQuadGeometry implements AutoCloseable {
    private final Thread owner=Thread.currentThread();
    private final GpuBuffer corners,indices;
    private boolean closed;
    public InstancedQuadGeometry(GpuDevice device) {
        var vertices=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
        vertices.putFloat(1).putFloat(-1).putFloat(1).putFloat(1).putFloat(-1).putFloat(1).putFloat(-1).putFloat(-1).flip();
        corners=device.createBuffer(()->"Overhaul instanced quad corners",GpuBuffer.USAGE_VERTEX,vertices);
        try {
            var elements=ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder());
            elements.putInt(0).putInt(1).putInt(2).putInt(2).putInt(3).putInt(0).flip();
            indices=device.createBuffer(()->"Overhaul instanced quad indices",GpuBuffer.USAGE_INDEX,elements);
        }catch(RuntimeException|Error failure){corners.close();throw failure;}
    }
    public void bind(RenderPass pass,GpuBufferSlice instances) {
        requireOwner();if(closed)throw new IllegalStateException("Instanced quad geometry is closed");
        pass.setVertexBuffer(0,corners.slice());pass.setVertexBuffer(1,instances);pass.setIndexBuffer(indices,IndexType.INT);
    }
    @Override public void close(){requireOwner();if(closed)return;indices.close();corners.close();closed=true;}
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Instanced geometry belongs to its render owner");}
}
