package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.overhaul.minecraft.blaze.BlazeBulkVertices;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(BufferBuilder.class)
abstract class BufferBuilderBulk121Mixin implements BlazeBulkVertices {
    @Shadow @Final private ByteBufferBuilder buffer;
    @Shadow @Final private VertexFormat.Mode mode;
    @Shadow @Final private boolean fullFormat;
    @Shadow @Final private int vertexSize;
    @Shadow private long vertexPointer;
    @Shadow private int vertices;
    @Shadow private void ensureBuilding(){}
    @Shadow private void endLastVertex(){}

    @Override public long overhaul$reserveEntityVertices(int count){
        if(!fullFormat||mode!=VertexFormat.Mode.QUADS||vertexSize!=36)return 0L;
        ensureBuilding();
        endLastVertex();
        vertices+=count;
        long first=buffer.reserve(vertexSize*count);
        vertexPointer=first+(long)vertexSize*(count-1);
        return first;
    }
}
