package dev.overhaul.shader.pack;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Active GLSL target directives and dimension-dependent allocations, independent of Vulkan ownership. */
public final class PackRenderTargets {
    private static final Pattern DIRECTIVE=Pattern.compile("\\bconst\\s+(int|bool|vec4)\\s+((?:colortex(?:[0-9]|1[0-5])|gcolor|gdepth|gnormal|composite|gaux[1-4]))(Format|ClearColor|Clear|MipmapEnabled)\\s*=\\s*([^;]+);");
    public enum Format { R8,RG8,RGB8,RGBA8,R16,RG16,RGB16,RGBA16,R16F,RG16F,RGB16F,RGBA16F,R32F,RG32F,RGB32F,RGBA32F,RGB10_A2,R11F_G11F_B10F }
    public record Color(float red,float green,float blue,float alpha){
        public Color{if(!Float.isFinite(red)||!Float.isFinite(green)||!Float.isFinite(blue)||!Float.isFinite(alpha))throw invalid("Nonfinite target clear color");}
    }
    public record Target(int index,Format format,boolean clear,Color color,boolean fogColor,String width,String height,boolean mipmapped){
        public Target(int index,Format format,boolean clear,Color color,boolean fogColor,String width,String height){this(index,format,clear,color,fogColor,width,height,false);}
        public int width(int viewport){return dimension(width,viewport);}
        public int height(int viewport){return dimension(height,viewport);}
    }
    private static final String[] ALIASES={"gcolor","gdepth","gnormal","composite","gaux1","gaux2","gaux3","gaux4"};
    private final Map<Integer,Target> targets;
    private PackRenderTargets(Map<Integer,Target> targets){this.targets=Map.copyOf(targets);}
    public Map<Integer,Target> targets(){return targets;}
    public static String stripDirectives(String source){return DIRECTIVE.matcher(source).replaceAll("");}
    static boolean hasDirective(String source){return DIRECTIVE.matcher(source).find();}
    /** Mipmap requests belong to the declaring pass, unlike persistent attachment formats/clear state. */
    public static Set<Integer> mipmapTargets(String source){
        Map<Integer,Boolean> states=new TreeMap<>();var matcher=DIRECTIVE.matcher(source.replaceAll("(?m)//.*$",""));
        while(matcher.find())if(matcher.group(3).equals("MipmapEnabled")){
            if(!matcher.group(1).equals("bool"))throw invalid("Mipmap directive requires const bool");
            String value=matcher.group(4).trim();if(!value.equals("true")&&!value.equals("false"))throw invalid("Mipmap directive requires a literal boolean");
            int index=colorIndex(matcher.group(2));boolean enabled=Boolean.parseBoolean(value);Boolean prior=states.putIfAbsent(index,enabled);
            if(prior!=null&&prior!=enabled)throw invalid("Conflicting mipmap directives in one program: "+matcher.group(2));
        }
        var enabled=new java.util.TreeSet<Integer>();for(var entry:states.entrySet())if(entry.getValue())enabled.add(entry.getKey());return Set.copyOf(enabled);
    }
    public static int colorIndex(String sampler){
        if(sampler.matches("colortex(?:[0-9]|1[0-5])"))return Integer.parseInt(sampler.substring(8));
        for(int index=0;index<ALIASES.length;index++)if(ALIASES[index].equals(sampler))return index;
        return -1;
    }
    public static int depthIndex(String sampler){return sampler.equals("gdepthtex")?0:sampler.matches("depthtex[0-2]")?sampler.charAt(8)-'0':-1;}
    private static int dimension(String value,int viewport){
        if(viewport<1)throw new IllegalArgumentException("Positive viewport required");
        try{
            double size=value.matches("[0-9]+")?Long.parseLong(value):Double.parseDouble(value)*viewport;
            if(!Double.isFinite(size)||size<1||size>32768)throw invalid("Render target dimension exceeds [1,32768]: "+value);
            return(int)size;
        }catch(NumberFormatException failure){throw invalid("Invalid render target dimension: "+value);}
    }
    public static final class Builder {
        private final ShaderPackProperties properties;
        private final Map<String,String> directives=new LinkedHashMap<>();
        private final Map<Integer,Boolean> used=new TreeMap<>();
        private final Set<Integer> mipmapped=new java.util.HashSet<>();
        private boolean legacyDepth;
        public Builder(ShaderPackProperties properties){this.properties=properties;}
        public void scan(String source){
            mipmapped.addAll(mipmapTargets(source));
            // Pack constants in block comments are metadata; line-commented constants are disabled.
            String text=source.replaceAll("(?m)//.*$","");var matcher=DIRECTIVE.matcher(text);
            while(matcher.find()){
                if(matcher.group(3).equals("MipmapEnabled")){used.put(colorIndex(matcher.group(2)),true);continue;}
                String key=colorIndex(matcher.group(2))+matcher.group(3),value=matcher.group(4).trim();
                String prior=directives.putIfAbsent(key,value);
                if(prior!=null&&!prior.equals(value))throw invalid("Conflicting pack target directive: "+matcher.group(2)+matcher.group(3));
                used.put(colorIndex(matcher.group(2)),true);
            }
        }
        public void requireTarget(int index){if(index<0||index>15)throw invalid("Invalid color target: "+index);used.put(index,true);}
        public void use(ShaderPackProgram code){use(code,true);}
        public void use(ShaderPackProgram code,boolean includeOutputs){
            legacyDepth|=code.descriptors().samplers().containsKey("gdepth");
            if(includeOutputs)for(int index:code.framebuffer().colorTargets())if(index>=0)used.put(index,true);
            for(String sampler:code.descriptors().samplers().keySet()){int index=colorIndex(sampler);if(index>=0)used.put(index,true);}
        }
        public PackRenderTargets build(){
            Map<Integer,Target> result=new LinkedHashMap<>();used.putIfAbsent(0,true);
            for(int index:used.keySet()){
                Format format;String raw=directives.getOrDefault(index+"Format",index==1&&legacyDepth?"RGBA32F":"RGBA8");
                try{format=Format.valueOf(raw.equals("RGBA")?"RGBA8":raw);}catch(IllegalArgumentException failure){throw invalid("Unsupported colortex format: "+raw);}
                boolean clear=bool(directives.getOrDefault(index+"Clear","true"));
                String[] size=properties.values().getOrDefault("size.buffer.colortex"+index,"1.0 1.0").split("\\s+");
                if(size.length!=2)throw invalid("Target size requires width and height");dimension(size[0],1024);dimension(size[1],1024);
                String rawColor=directives.get(index+"ClearColor");boolean fog=index==0&&rawColor==null;
                Color color=rawColor==null?(index==1?new Color(1,1,1,1):new Color(0,0,0,0)):color(rawColor);
                result.put(index,new Target(index,format,clear,color,fog,size[0],size[1],mipmapped.contains(index)));
            }
            return new PackRenderTargets(result);
        }
        private static boolean bool(String value){if(!value.equals("true")&&!value.equals("false"))throw invalid("Expected literal target boolean: "+value);return Boolean.parseBoolean(value);}
        private static Color color(String value){
            var matcher=Pattern.compile("vec4\\s*\\(([^()]*)\\)").matcher(value);
            if(!matcher.matches())throw invalid("Clear color must be a literal vec4: "+value);
            String[] parts=matcher.group(1).split(",");if(parts.length!=1&&parts.length!=4)throw invalid("Clear color requires one or four components");
            float[] components=new float[4];for(int index=0;index<4;index++)try{components[index]=Float.parseFloat(parts[parts.length==1?0:index].trim());}
            catch(NumberFormatException failure){throw invalid("Nonliteral clear color: "+value);}
            return new Color(components[0],components[1],components[2],components[3]);
        }
    }
    private static UnsupportedShaderException invalid(String message){return new UnsupportedShaderException(message);}
}
