package dev.overhaul.shader.compat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Thread-confined std140 values. The returned view never exposes mutable storage. */
public final class UniformBlock {
    private final ProgramBindings bindings;
    private final ByteBuffer data;

    public UniformBlock(ProgramBindings bindings) {
        this.bindings = Objects.requireNonNull(bindings,"bindings");
        data = ByteBuffer.allocateDirect(bindings.uniformBytes()).order(ByteOrder.nativeOrder());
    }

    public ProgramBindings bindings() { return bindings; }

    public void setFloat(String name, float value) {
        var uniform = require(name,"float"); data.putFloat(uniform.byteOffset(),value);
    }
    public void setInt(String name, int value) {
        var uniform = require(name,"int"); data.putInt(uniform.byteOffset(),value);
    }
    public void setBoolean(String name, boolean value) {
        var uniform = require(name,"bool"); data.putInt(uniform.byteOffset(),value ? 1 : 0);
    }
    public void setVector(String name, float... values) {
        if (values.length < 2 || values.length > 4) throw new IllegalArgumentException("Vector size must be 2..4");
        var uniform = require(name,"vec"+values.length);
        for (int index=0;index<values.length;index++) data.putFloat(uniform.byteOffset()+4*index,values[index]);
    }
    public void setIntVector(String name, int... values) {
        if (values.length < 2 || values.length > 4) throw new IllegalArgumentException("Vector size must be 2..4");
        var uniform = require(name,"ivec"+values.length);
        for (int index=0;index<values.length;index++) data.putInt(uniform.byteOffset()+4*index,values[index]);
    }

    /** Matrix data is column-major; every matrix column has std140 stride 16. */
    public void setMatrix(String name, int dimension, float[] columnMajor) {
        if (dimension < 2 || dimension > 4 || columnMajor.length != dimension*dimension) {
            throw new IllegalArgumentException("Matrix dimensions must be square and 2..4");
        }
        var uniform = require(name,"mat"+dimension);
        for (int column=0;column<dimension;column++) for (int row=0;row<dimension;row++) {
            data.putFloat(uniform.byteOffset()+column*16+row*4,columnMajor[column*dimension+row]);
        }
    }

    /** A snapshot avoids writes racing an asynchronous GPU upload. */
    public ByteBuffer snapshot() {
        ByteBuffer snapshot = ByteBuffer.allocateDirect(data.capacity()).order(ByteOrder.nativeOrder());
        snapshot.put(data.duplicate().clear()).flip();
        return snapshot.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    private ProgramBindings.Uniform require(String name, String expectedType) {
        ProgramBindings.Uniform uniform = bindings.uniforms().get(name);
        if (uniform == null || !expectedType.equals(uniform.type())) throw new IllegalArgumentException("Uniform " + name + " is not " + expectedType);
        return uniform;
    }
}
