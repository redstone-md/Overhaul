package dev.overhaul.core.model;

import dev.overhaul.core.memory.DirectSlabPool;
import java.nio.ByteBuffer;

/** Packs arbitrary captured quads; never invents cube faces or merges UV/material seams. */
public final class ModelMesher {
    private static final int[] TRIANGLES={0,1,2,0,2,3};

    public ModelMesh mesh(ModelSnapshot snapshot,DirectSlabPool pool) {
        int quads=snapshot.quads().size();
        int vertexBytes=Math.multiplyExact(quads,4*ModelMesh.VERTEX_STRIDE);
        int indexBytes=Math.multiplyExact(quads,6*4);
        int[] counts=new int[RenderLayer.values().length];
        for (ModelQuad quad:snapshot.quads()) counts[quad.layer().ordinal()]++;
        double[] centers=new double[counts[RenderLayer.TRANSLUCENT.ordinal()]*3];
        int[] bases=new int[counts[RenderLayer.TRANSLUCENT.ordinal()]];
        DirectSlabPool.Lease lease=pool.acquire(Math.addExact(vertexBytes,indexBytes));
        try {
            ByteBuffer output=lease.buffer(); int emitted=0,translucent=0;
            for (RenderLayer layer:RenderLayer.values()) for (ModelQuad quad:snapshot.quads()) if (quad.layer()==layer) {
                for (int vertex=0;vertex<4;vertex++) writeVertex(output,(emitted*4+vertex)*ModelMesh.VERTEX_STRIDE,quad,quad.vertex(vertex));
                ByteBuffer indices=output.duplicate().order(output.order()); indices.position(vertexBytes+emitted*24);
                writeQuadIndices(indices,emitted*4);
                if (layer==RenderLayer.TRANSLUCENT) {
                    centers[translucent*3]=snapshot.originX()+quad.centerX();
                    centers[translucent*3+1]=snapshot.originY()+quad.centerY();
                    centers[translucent*3+2]=snapshot.originZ()+quad.centerZ();
                    bases[translucent++]=emitted*4;
                }
                emitted++;
            }
            return new ModelMesh(lease,snapshot,counts,centers,bases);
        } catch (Throwable failure) {lease.close();throw failure;}
    }
    private static void writeVertex(ByteBuffer output,int offset,ModelQuad quad,ModelVertex vertex) {
        output.putFloat(offset,vertex.x()).putFloat(offset+4,vertex.y()).putFloat(offset+8,vertex.z());
        output.putShort(offset+12,unorm16(vertex.u())).putShort(offset+14,unorm16(vertex.v()));
        int color=vertex.rgba();
        for (int channel=0;channel<4;channel++) output.put(offset+16+channel,(byte)(color>>>(channel*8)));
        output.putShort(offset+20,unorm16(vertex.lightU())).putShort(offset+22,unorm16(vertex.lightV()));
        output.put(offset+24,snorm8(vertex.normalX())).put(offset+25,snorm8(vertex.normalY())).put(offset+26,snorm8(vertex.normalZ()));
        output.put(offset+27,(byte)Math.round(quad.alphaCutoff()*255)); output.putInt(offset+28,quad.materialId());
    }
    private static short unorm16(float value){return (short)Math.round(value*65535);}
    private static byte snorm8(float value){return (byte)Math.round(value*127);}
    static void writeQuadIndices(ByteBuffer destination,int base){for(int corner:TRIANGLES)destination.putInt(base+corner);}
}
