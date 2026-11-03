package dev.overhaul.shader.blaze;

import dev.overhaul.shader.compat.CoordinateConvention;
import dev.overhaul.shader.compat.PackProgramTranslator;
import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.compile.SpirvBinary;
import dev.overhaul.shader.compile.SpirvInterface;
import java.util.Map;
import dev.overhaul.shader.compat.UnsupportedShaderException;

/** Lowers resource-selected Blaze3D core programs through the shared descriptor ABI. */
public final class BlazeProgramCompiler implements AutoCloseable {
    private final ShadercCompiler compiler = new ShadercCompiler();
    private final PackProgramTranslator translator = new PackProgramTranslator();

    public record Program(SpirvBinary vertex, SpirvBinary fragment, ProgramBindings bindings) {}

    public synchronized Program compile(String name, String vertex, String fragment,
            ShadercCompiler.IncludeResolver includes) {
        return compile(name + ".vsh", name + ".fsh", vertex, fragment, includes);
    }

    public synchronized Program compile(String vertexName, String fragmentName, String vertex, String fragment,
            ShadercCompiler.IncludeResolver includes) {
        String vs = preprocess(vertexName, vertex, ShaderStage.VERTEX, includes);
        String fs = preprocess(fragmentName, fragment, ShaderStage.FRAGMENT, includes);
        var vertexStage=BlazeSourceLowering.lower(vs,true);
        var fragmentStage=BlazeSourceLowering.lower(fs,false);
        if(!vertexStage.flatVaryings().equals(fragmentStage.flatVaryings()))throw new UnsupportedShaderException("Blaze3D interpolation qualifiers do not match");
        for(var entry:fragmentStage.varyings().entrySet())if(!entry.getValue().equals(vertexStage.varyings().get(entry.getKey())))throw new UnsupportedShaderException("Blaze3D varying type mismatch: "+entry.getKey());
        var lowered = translator.translate(vertexStage.source(),fragmentStage.source(), CoordinateConvention.OPENGL_RENDER_TARGET);
        String vertexText=declarations(lowered.vertex(),lowered.bindings().attributes(),vertexStage.attributes(),"in",java.util.Set.of());
        vertexText=declarations(vertexText,lowered.bindings().varyings(),vertexStage.varyings(),"out",vertexStage.flatVaryings());
        String fragmentText=declarations(lowered.fragment(),lowered.bindings().varyings(),fragmentStage.varyings(),"in",fragmentStage.flatVaryings());
        var declared=lowered.bindings();
        var bindings=new ProgramBindings(declared.uniforms(),declared.uniformBytes(),declared.samplers(),declared.attributes(),
                declared.varyings(),vertexStage.attributes(),declared.samplerTypes());
        SpirvBinary vertexCode=compiler.compile(vertexName, vertexText, ShaderStage.VERTEX);
        SpirvBinary fragmentCode=compiler.compile(fragmentName, fragmentText, ShaderStage.FRAGMENT);
        return new Program(vertexCode, fragmentCode, SpirvInterface.active(bindings,vertexCode,fragmentCode));
    }

    private static String declarations(String source,Map<String,Integer> locations,Map<String,String> types,String direction,java.util.Set<String> flat){
        for(var entry:types.entrySet()){
            String name=entry.getKey(),type=entry.getValue();
            Integer location=locations.get(name);
            if(location==null)throw new UnsupportedShaderException("Missing stage declaration: "+name);
            String declaration="layout(location="+location+") "+direction+" "+BlazeSourceLowering.surrogate(type)+" "+name+";";
            if(!source.contains(declaration))throw new UnsupportedShaderException("Unrecognized translated varying declaration");
            source=source.replace(declaration,"layout(location="+location+") "+(flat.contains(name)?"flat ":"")+direction+" "+type+" "+name+";");
        }
        return source;
    }

    private String preprocess(String name, String source, ShaderStage stage, ShadercCompiler.IncludeResolver includes) {
        if (source.length() > 4_194_304) throw new IllegalArgumentException("Blaze3D shader source exceeds budget");
        String imports = source.replaceAll("(?m)^(\\s*)#moj_import\\s+", "$1#include ");
        String expanded = compiler.preprocess(name, imports, stage, Map.of(), includes);
        // These are shaderc's source-location metadata after include expansion,
        // not shader behavior. All other extensions remain rejected by translation.
        return expanded.replaceAll("(?m)^\\s*#line[^\\r\\n]*", "")
                .replaceAll("(?m)^\\s*#extension\\s+GL_GOOGLE_(?:cpp_style_line_directive|include_directive)[^\\r\\n]*", "");
    }

    @Override public synchronized void close() { compiler.close(); }
}
