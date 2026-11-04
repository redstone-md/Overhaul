package dev.overhaul.shader.pack;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/** Complete immutable 2D pack assets; neither a pack path nor an open archive escapes loading. */
public final class PackTextures {
    private static final Pattern NOISE=Pattern.compile("\\bconst\\s+int\\s+noiseTextureResolution\\s*=\\s*([^;]+);");
    private static final String NOISE_IMAGE="<procedural-noise>";
    private static final long MAX_DECODED=134_217_728,MAX_ENCODED=67_108_864;
    public record Image(int width,int height,byte[] rgba,boolean blur,boolean clamp){
        public Image{
            if(width<1||height<1||width>4096||height>4096||(long)width*height*4!=rgba.length)throw new IllegalArgumentException("Invalid pack RGBA8 texture");
            rgba=rgba.clone();
        }
        @Override public byte[] rgba(){return rgba.clone();}
        public ByteBuffer pixels(){return ByteBuffer.wrap(rgba).asReadOnlyBuffer();}
    }
    private record Binding(String stage,String sampler){}
    private final Map<String,Image> images;
    private final Map<Binding,String> bindings;
    private final Map<String,String> global;
    private final Map<String,Set<Integer>> deactivated;
    private final Map<String,Map<String,Image>> inputs;
    private final String noise;
    private PackTextures(Builder builder,Map<String,Image> images,java.util.List<CompiledShaderPack.Program> programs){
        this.images=Map.copyOf(images);bindings=Map.copyOf(builder.bindings);global=Map.copyOf(builder.global);noise=builder.noise;
        Map<String,Set<Integer>> snapshots=new LinkedHashMap<>();Map<String,Set<Integer>> flipped=new LinkedHashMap<>();
        for(var program:programs){
            Set<Integer> prior=flipped.computeIfAbsent(stage(program.stage()),ignored->new java.util.HashSet<>());
            snapshots.put(program.name(),Set.copyOf(prior));
            if(program.scene()||program.stage()==CompiledShaderPack.Stage.SHADOWCOMPOSITE||program.stage()==CompiledShaderPack.Stage.FINAL)continue;
            for(int index:program.code().framebuffer().colorTargets())if(index>=0&&program.state().flips().getOrDefault("colortex"+index,true))prior.add(index);
            for(var entry:program.state().flips().entrySet())if(entry.getValue()){int index=PackRenderTargets.colorIndex(entry.getKey());if(index>=0)prior.add(index);}
        }
        deactivated=Map.copyOf(snapshots);
        Map<String,Map<String,Image>> resolved=new LinkedHashMap<>();
        for(var program:programs){
            Map<String,Image> samplers=new LinkedHashMap<>();
            for(String name:program.code().descriptors().samplers().keySet()){
                Image image=resolve(program,name);if(image!=null)samplers.put(name,image);
            }
            resolved.put(program.name(),Map.copyOf(samplers));
        }
        inputs=Map.copyOf(resolved);
    }
    public Map<String,Image> images(){return images;}
    /** Stage overrides precede added custom samplers and the default noise image. */
    public Image image(CompiledShaderPack.Program program,String sampler){
        Map<String,Image> samplers=inputs.get(program.name());return samplers==null?null:samplers.get(sampler);
    }
    private Image resolve(CompiledShaderPack.Program program,String sampler){
        String path=null;int color=PackRenderTargets.colorIndex(sampler);
        if(color<0||!deactivated.getOrDefault(program.name(),Set.of()).contains(color))for(String alias:aliases(program,sampler)){
            path=bindings.get(new Binding(stage(program.stage()),alias));if(path!=null)break;
        }
        if(path==null)path=global.get(sampler);if(path==null&&sampler.equals("noisetex"))path=noise;
        return path==null?null:images.get(path);
    }
    private static java.util.List<String> aliases(CompiledShaderPack.Program program,String name){
        int color=PackRenderTargets.colorIndex(name);
        if(color>=0&&(!program.scene()||color>=4))return PackRenderTargets.colorNames(color);
        if(program.scene()&&Set.of("tex","texture","gtexture","u_MainSampler").contains(name))return java.util.List.of("tex","texture","gtexture","u_MainSampler");
        if(!program.scene()&&(name.equals("gdepthtex")||name.equals("depthtex0")))return java.util.List.of("gdepthtex","depthtex0");
        if(Set.of("shadow","watershadow","shadowtex0","shadowtex1").contains(name)){
            boolean water=program.code().descriptors().samplers().containsKey("watershadow");int depth=PackShadowSettings.depthIndex(name,program.code().descriptors().samplers().keySet());
            return depth==0?(water?java.util.List.of("shadowtex0","watershadow"):java.util.List.of("shadowtex0","shadow")):
                    water?java.util.List.of("shadowtex1","shadow"):java.util.List.of("shadowtex1");
        }
        return java.util.List.of(name);
    }
    public static boolean property(String key){return key.equals("texture.noise")||key.matches("texture\\.[A-Za-z0-9_]+\\.[A-Za-z_]\\w*")||key.matches("customTexture\\.[A-Za-z_]\\w*");}
    static boolean hasDirective(String source){return NOISE.matcher(source).find();}
    private static String stage(CompiledShaderPack.Stage stage){return switch(stage){
        case GBUFFER,SHADOW->"gbuffers";case FINAL,COMPOSITE->"composite";case SHADOWCOMPOSITE->"shadowcomp";
        default->stage.name().toLowerCase(java.util.Locale.ROOT);
    };}
    public static final class Builder {
        private final Map<Binding,String> bindings=new LinkedHashMap<>();
        private final Map<String,String> global=new LinkedHashMap<>();
        private final Set<String> paths=new java.util.LinkedHashSet<>();
        private String noise;
        private Integer noiseResolution;
        private boolean needsNoise;
        public Builder(ShaderPackProperties properties)throws IOException{
            for(var entry:new java.util.TreeMap<>(properties.values()).entrySet()){
                String key=entry.getKey();if(!property(key))continue;String path=path(entry.getValue());paths.add(path);
                if(key.equals("texture.noise")){noise=path;continue;}
                if(key.startsWith("customTexture.")){global.put(key.substring(14),path);continue;}
                String[] parts=key.split("\\.");
                if(!Set.of("begin","shadowcomp","prepare","gbuffers","deferred","composite").contains(parts[1]))throw unsupported("Unsupported custom texture stage: "+parts[1]);
                bindings.put(new Binding(parts[1],parts[2]),path);
            }
            if(paths.size()>64)throw unsupported("Pack image count exceeds 64");
        }
        public void scan(String source){
            var matcher=NOISE.matcher(source.replaceAll("(?m)//.*$",""));
            while(matcher.find()){
                int size;try{size=Integer.parseInt(matcher.group(1).trim());}catch(NumberFormatException failure){throw unsupported("noiseTextureResolution requires a literal integer");}
                if(size<1||size>4096)throw unsupported("noiseTextureResolution exceeds [1,4096]");
                if(noiseResolution!=null&&noiseResolution!=size)throw unsupported("Conflicting noiseTextureResolution");noiseResolution=size;
            }
        }
        public void use(CompiledShaderPack.Program program){
            needsNoise|=program.code().descriptors().samplers().containsKey("noisetex")
                    &&!bindings.containsKey(new Binding(stage(program.stage()),"noisetex"))&&!global.containsKey("noisetex");
        }
        public PackTextures load(ShaderPackSource source,java.util.List<CompiledShaderPack.Program> programs)throws IOException{
            Map<String,Image> loaded=new LinkedHashMap<>();Set<String> available=Set.copyOf(source.paths());long decoded=0,encoded=0;
            for(String path:paths){
                byte[] bytes=source.readBytes(path,16_777_216);encoded+=bytes.length;if(encoded>MAX_ENCODED)throw unsupported("Pack encoded image bytes exceed 64MiB");
                String meta=path+".mcmeta";PackTextureMetadata metadata=new PackTextureMetadata(false,false);
                if(available.contains(meta)){
                    byte[] metadataBytes=source.readBytes(meta,65_536);encoded+=metadataBytes.length;
                    if(encoded>MAX_ENCODED)throw unsupported("Pack encoded image bytes exceed 64MiB");
                    metadata=PackTextureMetadata.read(new String(metadataBytes,StandardCharsets.UTF_8));
                }
                Image image=PackPngDecoder.decode(bytes,metadata,MAX_DECODED-decoded);decoded+=(long)image.width()*image.height()*4;loaded.put(path,image);
            }
            if(needsNoise&&noise==null){
                int size=noiseResolution==null?256:noiseResolution;long bytes=(long)size*size*4;
                if(loaded.size()>=64||bytes>MAX_DECODED-decoded)throw unsupported("Procedural noise exceeds the pack image budget");
                byte[] rgba=new byte[(int)bytes];Random random=new Random(0);
                for(int x=0;x<size;x++)for(int y=0;y<size;y++){
                    int argb=random.nextInt(),offset=(y*size+x)*4;
                    rgba[offset]=(byte)(argb>>>16);rgba[offset+1]=(byte)(argb>>>8);rgba[offset+2]=(byte)argb;rgba[offset+3]=(byte)255;
                }
                noise=NOISE_IMAGE;loaded.put(noise,new Image(size,size,rgba,true,false));
            }
            return new PackTextures(this,loaded,programs);
        }
        private static String path(String value)throws IOException{
            if(value.isBlank()||value.indexOf(':')>=0||value.chars().anyMatch(Character::isWhitespace)||!value.toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
                throw unsupported("Only pack-local PNG custom textures are bound: "+value);
            return ShaderPackSource.normalize(value);
        }
    }
    private static UnsupportedShaderException unsupported(String message){return new UnsupportedShaderException(message);}
}
