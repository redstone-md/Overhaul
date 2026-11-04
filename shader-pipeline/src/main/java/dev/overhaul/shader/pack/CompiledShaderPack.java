package dev.overhaul.shader.pack;

import dev.overhaul.shader.compat.CoordinateConvention;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Immutable executable pack generation. Compilation never mutates the currently installed generation. */
public final class CompiledShaderPack {
    public enum Stage { BEGIN,SHADOW,SHADOWCOMPOSITE,PREPARE,GBUFFER,DEFERRED,COMPOSITE,FINAL }
    public record Program(String name,Stage stage,int ordinal,ShaderPackProgram code,ShaderPackProperties.ProgramState state,java.util.Set<Integer> mipmaps) {
        public Program(String name,Stage stage,int ordinal,ShaderPackProgram code,ShaderPackProperties.ProgramState state){this(name,stage,ordinal,code,state,java.util.Set.of());}
        public Program{mipmaps=java.util.Set.copyOf(mipmaps);for(int index:mipmaps)if(index<0||index>15)throw new IllegalArgumentException("Invalid mipmap color target");
            if(!mipmaps.isEmpty()&&(stage==Stage.GBUFFER||stage==Stage.SHADOW))throw new UnsupportedShaderException("Mipmap requests require a composite-style program: "+name);}
        public boolean scene(){return stage==Stage.GBUFFER||stage==Stage.SHADOW;}
    }
    public record Sources(String vertex,String fragment){}
    public static final int MAX_PROGRAMS=512;
    public static final long MAX_SOURCE_CHARACTERS=67_108_864;
    private final List<Program> programs;
    private final Map<String,Sources> sources;
    private final PackRenderTargets targets;
    private final PackShadowSettings shadow;
    private final PackTextures textures;
    private final List<PackDiagnostic> warnings;
    private final PackCustomUniforms customUniforms;
    private final PackSmoothing smoothing;
    private CompiledShaderPack(List<Program> programs,PackRenderTargets targets,PackShadowSettings shadow,Map<String,Sources> sources,PackTextures textures,List<PackDiagnostic> warnings,PackCustomUniforms customUniforms){this.programs=List.copyOf(programs);this.targets=targets;this.shadow=shadow;this.sources=Map.copyOf(sources);this.textures=textures;this.warnings=List.copyOf(warnings);this.customUniforms=customUniforms;
        List<String> texts=new ArrayList<>();for(Sources value:sources.values()){texts.add(value.vertex());texts.add(value.fragment());}smoothing=PackSmoothing.scan(texts);}
    public List<Program> programs(){return programs;}
    public PackSmoothing smoothing(){return smoothing;}
    /** Evaluated once per frame by whoever fills this generation's uniform blocks. */
    public PackCustomUniforms customUniforms(){return customUniforms;}
    /** Pack features this generation runs without. */
    public List<PackDiagnostic> warnings(){return warnings;}
    public PackRenderTargets targets(){return targets;}
    public PackShadowSettings shadow(){return shadow;}
    public PackTextures textures(){return textures;}
    public Sources sources(Program program){Sources value=sources.get(program.name());if(value==null)throw new IllegalArgumentException("Program belongs to another pack generation");return value;}

    public static CompiledShaderPack compile(ShaderPackSource source,Integer dimension,Map<String,Boolean> options,
                                              Map<String,String> macros,ShadercCompiler compiler)throws IOException{
        return compileForDimension(source,ShaderPackDimensions.legacyIdentifier(dimension),options,macros,compiler);
    }

    public static CompiledShaderPack compileForDimension(ShaderPackSource source,String dimension,Map<String,Boolean> options,
                                              Map<String,String> macros,ShadercCompiler compiler)throws IOException{
        ShaderPackCatalog catalog=ShaderPackCatalog.discoverForDimension(source,dimension,options,macros);
        List<PackDiagnostic> warnings=new ArrayList<>(catalog.warnings());
        if(catalog.programs().size()>MAX_PROGRAMS)throw new UnsupportedShaderException("Shader pack exceeds the 512-program generation bound");
        if(!catalog.diagnostics().isEmpty())throw new UnsupportedShaderException(String.join("\n",catalog.diagnostics()));
        List<Program> programs=new ArrayList<>();PackRenderTargets.Builder targets=new PackRenderTargets.Builder(catalog.properties());
        PackShadowSettings.Builder shadow=new PackShadowSettings.Builder(catalog.properties());
        PackTextures.Builder textures=new PackTextures.Builder(catalog.properties());
        Map<String,Sources> sources=new java.util.LinkedHashMap<>();long sourceCharacters=0;
        PackPreprocessor preprocessor=new PackPreprocessor(source,compiler,macros,options);
        for(ShaderPackCatalog.Program discovered:catalog.programs()){
            if(!discovered.state().enabled())continue;
            String name=discovered.name().substring(discovered.name().lastIndexOf('/')+1);
            if(name.startsWith("dh_")){warnings.add(PackDiagnostic.degraded(PackDiagnostic.Category.PROGRAM,discovered.name(),"Distant Horizons programs are not compiled; there is no Distant Horizons integration"));continue;}
            Sources expanded=preprocess(discovered,preprocessor);
            sourceCharacters+=(long)expanded.vertex().length()+expanded.fragment().length();if(sourceCharacters>MAX_SOURCE_CHARACTERS)throw new UnsupportedShaderException("Expanded shader-pack generation exceeds 64 million characters");
            sources.put(discovered.name(),expanded);
            programs.add(compileProgram(discovered,expanded,compiler,targets,shadow,textures));
        }
        programs.sort(Comparator.comparing(Program::stage).thenComparingInt(Program::ordinal).thenComparing(Program::name));
        if(programs.isEmpty())throw new UnsupportedShaderException("Shader pack has no enabled executable programs");
        return new CompiledShaderPack(programs,targets.build(),shadow.build(),sources,textures.load(source,programs),warnings,PackCustomUniforms.parse(catalog.properties().values()));
    }
    /** An unknown stage fails before any source is read. */
    public static Sources preprocess(ShaderPackCatalog.Program discovered,PackPreprocessor preprocessor)throws IOException{
        stage(discovered.name().substring(discovered.name().lastIndexOf('/')+1));
        return new Sources(preprocessor.process(discovered.vertexPath(),ShaderStage.VERTEX),preprocessor.process(discovered.fragmentPath(),ShaderStage.FRAGMENT));
    }
    /** One program of a generation, recorded into the builders that aggregate pack-wide requirements. */
    public static Program compileProgram(ShaderPackCatalog.Program discovered,Sources sources,ShadercCompiler compiler,
                                         PackRenderTargets.Builder targets,PackShadowSettings.Builder shadow,PackTextures.Builder textures){
        String name=discovered.name().substring(discovered.name().lastIndexOf('/')+1);
        Stage stage=stage(name);
        String vertex=sources.vertex(),fragment=sources.fragment();
        targets.scan(fragment);
        shadow.scan(vertex);shadow.scan(fragment);
        textures.scan(vertex);textures.scan(fragment);
        ShaderPackProgram code=ShaderPackProgram.compile(discovered.name(),vertex,fragment,compiler,CoordinateConvention.OPENGL_RENDER_TARGET,
                discovered.state().alphaTest().orElse(null));
        boolean shadowOutputs=stage==Stage.SHADOW||stage==Stage.SHADOWCOMPOSITE;
        for(String target:discovered.state().flips().keySet())if(shadowOutputs?PackShadowSettings.colorIndex(target)<0:PackRenderTargets.colorIndex(target)<0)
            throw new UnsupportedShaderException("Buffer flip uses the wrong output namespace: "+discovered.name()+"."+target);
        for(String target:discovered.state().blends().keySet())if(!target.equals("*")&&(shadowOutputs?PackShadowSettings.colorIndex(target)<0:PackRenderTargets.colorIndex(target)<0))
            throw new UnsupportedShaderException("Blend uses the wrong output namespace: "+discovered.name()+"."+target);
        targets.use(code,!shadowOutputs);shadow.use(code,shadowOutputs);
        Program program=new Program(discovered.name(),stage,ordinal(name),code,discovered.state(),PackRenderTargets.mipmapTargets(fragment));
        textures.use(program);
        return program;
    }
    /** Stage of a bare program name; an unknown name fails. */
    public static Stage stage(String name){
        if(name.startsWith("gbuffers_"))return Stage.GBUFFER;
        if(name.equals("shadow")||name.startsWith("shadow_"))return Stage.SHADOW;
        if(name.matches("shadowcomp[0-9]*"))return Stage.SHADOWCOMPOSITE;
        for(Stage stage:Stage.values())if(stage!=Stage.GBUFFER&&stage!=Stage.SHADOW
                &&name.matches(stage.name().toLowerCase(java.util.Locale.ROOT)+"[0-9]*"))return stage;
        throw new UnsupportedShaderException("Unknown shader pack program stage: "+name);
    }
    /** Position within a stage: a bare name runs first, then its numeric suffixes in order. */
    public static int ordinal(String name){
        int index=name.length();while(index>0&&Character.isDigit(name.charAt(index-1)))index--;
        try{return index==name.length()?0:Math.addExact(1,Integer.parseInt(name.substring(index)));}
        catch(ArithmeticException|NumberFormatException invalid){throw new UnsupportedShaderException("Program suffix exceeds supported range: "+name);}
    }
}
