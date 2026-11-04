package dev.overhaul.minecraft.blaze;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable version boundary; native owners retain no game format or uploaded-buffer object. */
public record BlazeMeshState(Layout layout,int vertexCount,int indexCount,int topology,int indexBytes,IndexPattern pattern) {
    public enum IndexPattern { SEQUENTIAL,QUADS,EXPANDED_LINES }
    public BlazeMeshState {
        Objects.requireNonNull(layout,"layout");Objects.requireNonNull(pattern,"pattern");
        if(vertexCount<0||indexCount<0)throw new IllegalArgumentException("Negative mesh count");
        if(indexBytes!=2&&indexBytes!=4)throw new IllegalArgumentException("Index width must be two or four bytes");
        if(topology<0||topology>6)throw new IllegalArgumentException("Unsupported mesh topology: "+topology);
    }
    public record Field(String name,int offset,int nativeFormat) {
        public Field {
            Objects.requireNonNull(name,"name");
            if(name.isEmpty()||offset<0||nativeFormat==0)throw new IllegalArgumentException("Invalid native vertex field");
        }
    }
    public record Layout(int stride,List<Field> fields) {
        public Layout {
            if(stride<0)throw new IllegalArgumentException("Negative vertex stride");
            fields=List.copyOf(fields);var names=new HashSet<String>();
            for(Field field:fields){
                if(field.offset()>=stride||!names.add(field.name()))throw new IllegalArgumentException("Invalid or duplicate vertex field: "+field.name());
            }
        }
    }
}
