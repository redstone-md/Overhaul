package dev.overhaul.vulkan;

import dev.overhaul.shader.compat.ProgramBindings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import static org.lwjgl.vulkan.VK10.*;

/** One authoritative interleaved layout from compiler-assigned locations and GLSL types. */
final class PackVertexLayout {
    record Field(String name,int components,int offset,int location,int format) {}
    final List<Field> fields;
    final int stride;

    PackVertexLayout(ProgramBindings bindings) {
        List<Field> fields=new ArrayList<>(); int offset=0;
        var names=bindings.attributes().keySet().stream().sorted(Comparator.comparingInt(bindings.attributes()::get)).toList();
        if (names.size()>16) throw new IllegalArgumentException("Diagnostic pack renderer supports at most 16 vertex inputs");
        for (String name:names) {
            String type=bindings.attributeTypes().get(name);
            int components=switch (type) { case "float"->1; case "vec2"->2; case "vec3"->3; case "vec4"->4;
                default->throw new IllegalArgumentException("Unsupported pack vertex type: "+type); };
            int format=switch (components) { case 1->VK_FORMAT_R32_SFLOAT; case 2->VK_FORMAT_R32G32_SFLOAT;
                case 3->VK_FORMAT_R32G32B32_SFLOAT; default->VK_FORMAT_R32G32B32A32_SFLOAT; };
            fields.add(new Field(name,components,offset,bindings.attributes().get(name),format)); offset+=components*4;
        }
        if (offset==0) throw new IllegalArgumentException("Pack renderer requires explicit vertex inputs");
        this.fields=List.copyOf(fields); stride=offset;
    }
    List<GraphicsPipeline.Attribute> attributes() {
        return fields.stream().map(field->new GraphicsPipeline.Attribute(field.location(),0,field.format(),field.offset())).toList();
    }
}
