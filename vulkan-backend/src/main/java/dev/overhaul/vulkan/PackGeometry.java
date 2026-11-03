package dev.overhaul.vulkan;

import dev.overhaul.shader.compat.ProgramBindings;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

/** Immutable named vertex attributes packed against the generated shader ABI. */
public final class PackGeometry {
    private final ProgramBindings bindings;
    private final ByteBuffer vertices;
    private final ByteBuffer indices;
    private final int indexCount;

    public PackGeometry(ProgramBindings bindings,int vertexCount,Map<String,float[]> attributes,int[] indices) {
        PackVertexLayout layout=new PackVertexLayout(bindings);
        if (vertexCount<3 || (long)vertexCount*layout.stride>67_108_864 || indices.length<3 || indices.length%3!=0) {
            throw new IllegalArgumentException("Invalid triangle-list geometry dimensions");
        }
        if (!attributes.keySet().equals(bindings.attributes().keySet())) throw new IllegalArgumentException("Vertex attributes must exactly match the shader ABI");
        this.bindings=bindings;
        this.vertices=ByteBuffer.allocateDirect(vertexCount*layout.stride).order(ByteOrder.nativeOrder());
        for (var field:layout.fields) {
            float[] values=attributes.get(field.name());
            if (values.length!=(long)vertexCount*field.components()) throw new IllegalArgumentException("Wrong attribute length: "+field.name());
            for (int vertex=0;vertex<vertexCount;vertex++) for (int component=0;component<field.components();component++) {
                float value=values[vertex*field.components()+component];
                if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite vertex input: "+field.name());
                this.vertices.putFloat(vertex*layout.stride+field.offset()+component*4,value);
            }
        }
        if ((long)indices.length*4>67_108_864) throw new IllegalArgumentException("Index data exceeds size limit");
        this.indices=ByteBuffer.allocateDirect(indices.length*4).order(ByteOrder.nativeOrder());
        for (int index:indices) {
            if (index<0 || index>=vertexCount) throw new IllegalArgumentException("Index outside vertex data");
            this.indices.putInt(index);
        }
        this.indices.flip(); indexCount=indices.length;
    }
    ProgramBindings bindings() { return bindings; }
    ByteBuffer vertices() { return vertices.asReadOnlyBuffer().order(ByteOrder.nativeOrder()); }
    ByteBuffer indices() { return indices.asReadOnlyBuffer().order(ByteOrder.nativeOrder()); }
    public int indexCount() { return indexCount; }
}
