package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.EffectProgram;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.shaders.Shader;
import com.mojang.blaze3d.shaders.Uniform;
import dev.overhaul.minecraft.blaze.mixin.EffectProgramConstructorAccess;
import dev.overhaul.minecraft.blaze.mixin.ProgramConstructorAccess;
import dev.overhaul.shader.blaze.BlazeProgramCompiler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Device-confined program identities own actual SPIR-V and reflection; no identity is an OpenGL handle. */
public final class BlazePrograms implements AutoCloseable {
    private static final int MAX_PROGRAMS=4096,MAX_SOURCE=4_194_304;
    private static final Pattern SECTION_OFFSET=Pattern.compile("uniform\\s+vec3\\s+ChunkOffset\\s*;");
    private static final class Entry {
        BlazeProgramCompiler.Program program;
        Map<String,Integer> locations=Map.of();
        final Map<Integer,String> samplerLocations=new HashMap<>();
        final Map<String,Integer> samplerUnits=new HashMap<>();
        Supplier<BlazeShaderSnapshot> snapshot;
        String name="unlinked";
        boolean foreign;
        String vertexName,fragmentName,vertexSource,fragmentSource;
        BlazeProgramCompiler.Program sections,compact,compactSections;
        boolean sectionsTried,compactTried,compactSectionsTried;
        void releaseAll(Consumer<BlazeProgramCompiler.Program> released){
            if(program!=null)released.accept(program);if(sections!=null)released.accept(sections);
            if(compact!=null)released.accept(compact);if(compactSections!=null)released.accept(compactSections);
        }
    }
    private final Thread owner=Thread.currentThread();
    private final BlazeProgramCompiler compiler;
    private final Consumer<BlazeProgramCompiler.Program> released;
    private final Map<Integer,Entry> programs=new HashMap<>();
    private int next=1,active,unknownActive;
    private boolean closed;

    public BlazePrograms(){this(program->{});}
    public BlazePrograms(Consumer<BlazeProgramCompiler.Program> released){this.released=java.util.Objects.requireNonNull(released);compiler=new BlazeProgramCompiler();}

    public static BlazePrograms current(){var renderer=BlazeFrontend.current();return renderer==null?null:renderer.programs();}
    public BlazeProgramCompiler compiler(){requireOpen();return compiler;}
    public int create(){requireOpen();if(programs.size()>=MAX_PROGRAMS||next<=0)throw new IllegalStateException("Blaze program capacity exceeded");int id=next++;programs.put(id,new Entry());return id;}
    public Program stage(Program.Type type,String name,InputStream stream,GlslPreprocessor preprocessor,boolean effect)throws IOException{
        requireOpen();byte[] bytes=stream.readNBytes(MAX_SOURCE+1);if(bytes.length>MAX_SOURCE)throw new IOException("Shader source exceeds budget: "+name);
        StringBuilder source=new StringBuilder();
        for(String part:preprocessor.process(new String(bytes,StandardCharsets.UTF_8))){if(part.length()>MAX_SOURCE-source.length())throw new IOException("Expanded shader exceeds budget: "+name);source.append(part);}
        Program result=effect?EffectProgramConstructorAccess.overhaul$create(type,0,name):ProgramConstructorAccess.overhaul$create(type,0,name);
        ((BlazeStageAccess)result).overhaul$stage(new BlazeStageAccess.Stage(this,type,name+type.getExtension(),source.toString()));return result;
    }
    public void link(Shader shader){
        requireOpen();Entry entry=entry(shader.getId());
        try{
            var vertex=stage(shader.getVertexProgram());var fragment=stage(shader.getFragmentProgram());
            if(vertex.type()!=Program.Type.VERTEX||fragment.type()!=Program.Type.FRAGMENT)throw new IllegalArgumentException("Shader stages have incompatible types");
            var geometry=BlazeAttachedStages.takeGeometry(shader.getId());
            entry.name=vertex.name()+(geometry==null?"":" + "+geometry.name())+" + "+fragment.name();
            entry.program=compiler.compile(vertex.name(),geometry==null?null:geometry.name(),fragment.name(),vertex.source(),geometry==null?null:geometry.source(),fragment.source(),
                    (requested,requester,relative)->{throw new IllegalArgumentException("Unresolved vanilla shader import: "+requested);});
            bindLocations(entry);
            if(geometry==null&&SECTION_OFFSET.matcher(vertex.source()).find()){entry.vertexName=vertex.name();entry.fragmentName=fragment.name();entry.fragmentSource=fragment.source();entry.vertexSource=vertex.source();}
        }catch(RuntimeException|Error failure){programs.remove(shader.getId());throw failure;}
    }
    private static void bindLocations(Entry entry){
        Map<String,Integer> locations=new LinkedHashMap<>();var names=new TreeSet<>(entry.program.bindings().uniforms().keySet());names.addAll(entry.program.bindings().samplers().keySet());
        for(String name:names)locations.put(name,locations.size());entry.locations=Map.copyOf(locations);
        for(String name:entry.program.bindings().samplers().keySet()){entry.samplerLocations.put(locations.get(name),name);entry.samplerUnits.put(name,0);}
    }
    /** A program another renderer compiled from its own sources, under an identity from {@link #create()}. */
    public void linkForeign(int id,String name,BlazeProgramCompiler.Program program){requireOpen();var entry=entry(id);entry.name=name;entry.foreign=true;entry.program=program;bindLocations(entry);}
    /** Names a program that could not compile, so draws using it report what they lost. */
    public void nameForeign(int id,String name){requireOpen();var entry=entry(id);entry.name=name;entry.foreign=true;}
    public boolean linked(int id){requireOpen();var entry=programs.get(id);return entry!=null&&entry.program!=null;}
    public boolean known(int id){requireOpen();return programs.containsKey(id);}
    /** A renderer selected this name itself: a name without a compiled program must draw nothing rather than with the previous program. */
    public void useForeign(int id){
        requireOpen();
        if(id==0||linked(id)){use(id);return;}
        active=0;unknownActive=id;
    }
    boolean activeForeign(){return active!=0&&entry(active).foreign;}
    private BlazeStageAccess.Stage stage(Program program){
        var stage=((BlazeStageAccess)program).overhaul$stage();if(stage==null||stage.owner()!=this)throw new IllegalArgumentException("Foreign shader stage");return stage;
    }
    public BlazeProgramCompiler.Program compiled(int id){requireOpen();var program=entry(id).program;if(program==null)throw new IllegalStateException("Shader has not linked");return program;}
    public int uniformLocation(int id,CharSequence name){requireOpen();return entry(id).locations.getOrDefault(name.toString(),-1);}
    public int attributeLocation(int id,CharSequence name){return compiled(id).bindings().attributes().getOrDefault(name.toString(),-1);}
    public void registerSnapshot(int id,Supplier<BlazeShaderSnapshot> snapshot){requireOpen();entry(id).snapshot=java.util.Objects.requireNonNull(snapshot);}
    public void use(int id){requireOpen();if(id!=0)compiled(id);active=id;unknownActive=0;}
    public void samplerUnit(int location,int unit){
        requireOpen();if(location==-1)return;if(active==0)throw new IllegalStateException("Sampler assignment requires an active program");
        var entry=entry(active);String name=entry.samplerLocations.get(location);if(name==null)throw new IllegalArgumentException("Location is not an active sampler");entry.samplerUnits.put(name,unit);
    }
    public BlazeShaderSnapshot snapshot(int id,Map<String,Uniform> uniforms){return BlazeShaderSnapshot.capture(compiled(id),uniforms,boundTextures(id));}
    /** The shader's own source with its section offset as a vertex input: resource packs that replace the shader keep working. */
    public BlazeProgramCompiler.Program sectionProgram(int id){
        requireOpen();var entry=entry(id);if(entry.sectionsTried||entry.vertexSource==null)return entry.sections;
        entry.sectionsTried=true;
        try{entry.sections=variant(entry,sectionSource(entry));}
        catch(RuntimeException failure){BlazeGlBoundary.reportOnce("section program "+entry.name,"Terrain shader "+entry.name+" cannot read its section offset per instance, so it draws section by section: "+failure.getMessage());}
        return entry.sections;
    }
    private static String sectionSource(Entry entry){return SECTION_OFFSET.matcher(entry.vertexSource).replaceFirst("in vec3 "+BlazeSectionShape.OFFSET_ATTRIBUTE+";").replaceAll("\\bChunkOffset\\b",BlazeSectionShape.OFFSET_ATTRIBUTE);}
    private BlazeProgramCompiler.Program variant(Entry entry,String vertex){
        return compiler.compile(entry.vertexName,entry.fragmentName,vertex,entry.fragmentSource,(requested,requester,relative)->{throw new IllegalArgumentException("Unresolved vanilla shader import: "+requested);});
    }
    /** The shader as written, reading compact vertices, for code that draws section buffers itself with the section offset uniform. */
    BlazeProgramCompiler.Program compactProgram(int id){
        requireOpen();var entry=entry(id);if(entry.compactTried)return entry.compact;
        entry.compactTried=true;
        if(entry.vertexSource!=null)entry.compact=compactVariant(entry,entry.vertexSource,entry.program);
        return entry.compact;
    }
    public BlazeProgramCompiler.Program compactSectionProgram(int id){
        var sections=sectionProgram(id);var entry=entry(id);if(entry.compactSectionsTried||sections==null)return entry.compactSections;
        entry.compactSectionsTried=true;
        entry.compactSections=compactVariant(entry,sectionSource(entry),sections);
        return entry.compactSections;
    }
    private BlazeProgramCompiler.Program compactVariant(Entry entry,String vertex,BlazeProgramCompiler.Program original){
        String why;
        try{
            String source=BlazeCompactSection.vertexSource(vertex);
            if(source==null)why="it declares no position attribute";
            else{
                var program=variant(entry,source);
                if(BlazeCompactSection.replaces(program,original))return program;
                released.accept(program);why="it reads other vertex data, or its uniforms change";
            }
        }catch(RuntimeException failure){why=failure.getMessage();}
        BlazeGlBoundary.reportOnce("compact program "+entry.name,"Terrain shader "+entry.name+" cannot read compact vertices, so terrain stays in full vertices: "+why);
        return null;
    }
    public BlazeShaderSnapshot sectionSnapshot(int id,Map<String,Uniform> uniforms){return BlazeShaderSnapshot.capture(entry(id).sections,uniforms,boundTextures(id));}
    private Map<String,Object> boundTextures(int id){
        requireOpen();var renderer=BlazeFrontend.require();if(renderer.programs()!=this)throw new IllegalStateException("Foreign shader device");
        Map<String,Object> textures=new LinkedHashMap<>();for(var sampler:entry(id).samplerUnits.entrySet())textures.put(sampler.getKey(),renderer.textures().boundId(sampler.getValue()));
        return textures;
    }
    /** Names the active program when a draw cannot use it, because its shader instance never finished constructing. */
    int activeId(){return active;}
    public String incompleteActive(){
        requireOpen();
        if(unknownActive!=0){var entry=programs.get(unknownActive);return entry==null?"without native path "+unknownActive:entry.name;}
        if(active==0)return null;var entry=entry(active);return entry.snapshot==null?entry.name:null;
    }
    public BlazeShaderSnapshot activeSnapshot(){requireOpen();if(active==0)throw new IllegalStateException("No active Blaze shader");var supplier=entry(active).snapshot;if(supplier==null)throw new IllegalStateException("Shader construction has not completed");return supplier.get();}
    private int lastId;
    private Entry lastEntry;
    public void release(int id){requireOwner();lastEntry=null;var entry=programs.remove(id);if(active==id)active=0;if(entry!=null)entry.releaseAll(released);}
    private Entry entry(int id){
        if(id==lastId&&lastEntry!=null)return lastEntry;
        var entry=programs.get(id);if(entry==null)throw new IllegalArgumentException("Unknown Blaze program: "+id);
        lastId=id;lastEntry=entry;return entry;
    }
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Blaze programs belong to another thread");}
    private void requireOpen(){requireOwner();if(closed)throw new IllegalStateException("Blaze programs are closed");}
    @Override public void close(){
        requireOwner();if(closed)return;closed=true;active=0;
        try{for(var entry:programs.values())entry.releaseAll(released);}
        finally{programs.clear();compiler.close();}
    }
}
