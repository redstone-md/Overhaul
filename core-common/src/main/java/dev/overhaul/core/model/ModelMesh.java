package dev.overhaul.core.model;

import dev.overhaul.core.memory.DirectSlabPool;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Exclusive pooled baked geometry. Borrowed views expire when this mesh closes. */
public final class ModelMesh implements AutoCloseable {
    public static final int VERTEX_STRIDE=32;
    private final DirectSlabPool.Lease lease;
    private final int quadCount;
    private final int[] counts;
    private final long revision;
    private final double originX,originY,originZ;
    private final double[] translucentCenters;
    private final int[] translucentBases;

    ModelMesh(DirectSlabPool.Lease lease,ModelSnapshot snapshot,int[] counts,double[] centers,int[] bases) {
        this.lease=lease; quadCount=snapshot.quads().size(); this.counts=counts.clone(); revision=snapshot.revision();
        originX=snapshot.originX(); originY=snapshot.originY(); originZ=snapshot.originZ();
        translucentCenters=centers; translucentBases=bases;
    }
    public int vertexCount(){return quadCount*4;} public int indexCount(){return quadCount*6;}
    public int indexCount(RenderLayer layer){return counts[layer.ordinal()]*6;}
    public int firstIndex(RenderLayer layer){int result=0;for(int i=0;i<layer.ordinal();i++)result+=counts[i]*6;return result;}
    public long revision(){return revision;}
    public double originX(){return originX;} public double originY(){return originY;} public double originZ(){return originZ;}
    public ByteBuffer vertexBuffer(){return view(0,vertexCount()*VERTEX_STRIDE);}
    public ByteBuffer indexBuffer(){return view(vertexCount()*VERTEX_STRIDE,indexCount()*4);}
    public ModelTranslucentSorter newTranslucentSorter(){return new ModelTranslucentSorter(translucentCenters,translucentBases);}
    private ByteBuffer view(int offset,int bytes){
        ByteBuffer data=lease.buffer(); data.position(offset).limit(offset+bytes);
        return data.slice().asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }
    @Override public void close(){lease.close();}
}
