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
        // Translation assigns one stable location schema; linking uses the optimized interfaces below.
        // Temporary declarations are removed before compilation, never given invented vertex values.
        String vertexSchema=varyingSchema(vertexStage),fragmentSchema=varyingSchema(fragmentStage);
        for(var entry:fragmentStage.varyings().entrySet())if(!vertexStage.varyings().containsKey(entry.getKey()))
            vertexSchema+="\nvarying "+BlazeSourceLowering.schemaType(entry.getValue())+" "+entry.getKey()+";\n";
        var lowered = translator.translate(vertexSchema,fragmentSchema, CoordinateConvention.OPENGL_RENDER_TARGET);
        String vertexText=declarations(lowered.vertex(),lowered.bindings().attributes(),vertexStage.attributes(),"in",java.util.Set.of(),false);
        vertexText=declarations(vertexText,lowered.bindings().varyings(),vertexStage.varyings(),"out",vertexStage.flatVaryings(),true);
        for(var entry:fragmentStage.varyings().entrySet())if(!vertexStage.varyings().containsKey(entry.getKey()))
            vertexText=vertexText.replace("layout(location="+lowered.bindings().varyings().get(entry.getKey())+") out "+BlazeSourceLowering.schemaType(entry.getValue())+" "+entry.getKey()+";","");
        String fragmentText=declarations(lowered.fragment(),lowered.bindings().varyings(),fragmentStage.varyings(),"in",fragmentStage.flatVaryings(),true);
        var declared=lowered.bindings();
        var bindings=new ProgramBindings(declared.uniforms(),declared.uniformBytes(),declared.samplers(),declared.attributes(),
                declared.varyings(),vertexStage.attributes(),declared.samplerTypes());
        SpirvBinary vertexCode=compiler.compile(vertexName, vertexText, ShaderStage.VERTEX);
        SpirvBinary fragmentCode=compiler.compile(fragmentName, fragmentText, ShaderStage.FRAGMENT);
        validateVaryings(vertexStage,fragmentStage,lowered.bindings().varyings(),vertexCode,fragmentCode);
        return new Program(vertexCode, fragmentCode, SpirvInterface.active(bindings,vertexCode,fragmentCode));
    }

    private static String varyingSchema(BlazeSourceLowering.Stage stage){
        String source=stage.source();
        for(var entry:stage.varyings().entrySet())source=source.replace("varying "+BlazeSourceLowering.surrogate(entry.getValue())+" "+entry.getKey()+";","varying "+BlazeSourceLowering.schemaType(entry.getValue())+" "+entry.getKey()+";");
        return source;
    }

    private static void validateVaryings(BlazeSourceLowering.Stage vertex,BlazeSourceLowering.Stage fragment,Map<String,Integer> locations,SpirvBinary vertexCode,SpirvBinary fragmentCode){
        var inputs=SpirvInterface.inputLocations(fragmentCode);var outputs=SpirvInterface.outputLocations(vertexCode);
        for(var entry:fragment.varyings().entrySet()){
            String name=entry.getKey();int location=locations.get(name);if(!inputs.contains(location))continue;
            if(!entry.getValue().equals(vertex.varyings().get(name))||!outputs.contains(location))
                throw new UnsupportedShaderException("Blaze3D active fragment input has no matching vertex output: "+name);
            if(vertex.flatVaryings().contains(name)!=fragment.flatVaryings().contains(name))
                throw new UnsupportedShaderException("Blaze3D active interpolation qualifiers do not match: "+name);
        }
    }

    private static String declarations(String source,Map<String,Integer> locations,Map<String,String> types,String direction,java.util.Set<String> flat,boolean varying){
        for(var entry:types.entrySet()){
            String name=entry.getKey(),type=entry.getValue();
            Integer location=locations.get(name);
            if(location==null)throw new UnsupportedShaderException("Missing stage declaration: "+name);
            String declaration="layout(location="+location+") "+direction+" "+(varying?BlazeSourceLowering.schemaType(type):BlazeSourceLowering.surrogate(type))+" "+name+";";
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
