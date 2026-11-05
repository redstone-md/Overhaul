package dev.overhaul.shader.pack;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Active shadow camera and image directives, isolated from the main colortex namespace. */
public final class PackShadowSettings {
    private static final Pattern DIRECTIVE=Pattern.compile("\\bconst\\s+(int|float|bool)\\s+(shadowMapResolution|shadowDistance|shadowDistanceRenderMul|shadowIntervalSize|shadowMapFov|shadowNearPlane|shadowFarPlane|sunPathRotation|shadowHardwareFiltering[01]?|generateShadow(?:Color)?Mipmap|shadowtex[01]?(?:Nearest|Mipmap)|shadow[01]MinMagNearest|shadow(?:color|Color)[01](?:Nearest|Mipmap(?:Enabled)?|MinMagNearest))\\s*=\\s*([^;]+);");
    private final int resolution;
    private final float distance,renderMultiplier,interval,near,far,sunPathRotation;
    private final Float fov;
    private final boolean[] nearestDepth,nearestColor,mipmappedDepth,mipmappedColor;
    private final PackRenderTargets colors;
    private PackShadowSettings(Builder builder){
        resolution=builder.integer("shadowMapResolution",1024,1,16384);
        distance=builder.number("shadowDistance",160);renderMultiplier=builder.number("shadowDistanceRenderMul",-1);
        interval=builder.number("shadowIntervalSize",2);sunPathRotation=builder.number("sunPathRotation",0);
        near=builder.number("shadowNearPlane",-1);far=builder.number("shadowFarPlane",-1);
        fov=builder.directives.containsKey("shadowMapFov")?builder.number("shadowMapFov",90):null;
        if(distance<=0||interval<0||fov!=null&&(fov<=0||fov>=180)||near!=-1&&far!=-1&&far<=near)
            throw invalid("Invalid shadow camera extent, snapping interval or clip range");
        nearestDepth=builder.nearestDepth.clone();nearestColor=builder.nearestColor.clone();
        mipmappedDepth=builder.mipmappedDepth.clone();mipmappedColor=builder.mipmappedColor.clone();
        colors=builder.colors.build();
    }
    private PackShadowSettings(PackShadowSettings source,int resolution,float distance){
        this.resolution=resolution;this.distance=distance;renderMultiplier=1;interval=source.interval;near=source.near;far=source.far;sunPathRotation=source.sunPathRotation;fov=source.fov;
        nearestDepth=source.nearestDepth;nearestColor=source.nearestColor;mipmappedDepth=source.mipmappedDepth;mipmappedColor=source.mipmappedColor;colors=source.colors;
    }
    /** An owned policy replaces only the camera extent, so caster bounds honour its distance exactly. */
    public PackShadowSettings withCamera(int resolution,float distance){
        if(resolution<1||!Float.isFinite(distance)||distance<=0)throw invalid("Invalid owned shadow camera extent");
        return new PackShadowSettings(this,resolution,distance);
    }
    public int resolution(){return resolution;}
    public float distance(){return distance;}
    public float renderMultiplier(){return renderMultiplier;}
    public float interval(){return interval;}
    public float near(){return near;}
    public float far(){return far;}
    public float sunPathRotation(){return sunPathRotation;}
    public Float fov(){return fov;}
    public PackRenderTargets colors(){return colors;}
    public boolean nearestDepth(int index){return nearestDepth[index];}
    public boolean nearestColor(int index){return nearestColor[index];}
    public boolean mipmappedDepth(int index){return mipmappedDepth[index];}
    public boolean mipmappedColor(int index){return mipmappedColor[index];}
    public static int depthIndex(String name){return switch(name){case "shadow","watershadow","shadowtex0","shadowtex0HW"->0;case "shadowtex1","shadowtex1HW"->1;default->-1;};}
    public static int depthIndex(String name,java.util.Set<String> samplers){return name.equals("shadow")&&samplers.contains("watershadow")?1:depthIndex(name);}
    public static int colorIndex(String name){return switch(name){case "shadowcolor","shadowcolor0"->0;case "shadowcolor1"->1;default->-1;};}
    public static String stripDirectives(String source){
        // Camera constants remain executable GLSL: packs commonly use their resolution and distance in expressions.
        return PackRenderTargets.stripDirectives(remapColors(source));
    }
    static boolean hasDirective(String source){return DIRECTIVE.matcher(source).find()||PackRenderTargets.hasDirective(remapColors(source));}
    private static String remapColors(String source){return source.replaceAll("\\bshadow(?:color|Color)([01])(?=Format|ClearColor|Clear|MipmapEnabled)","colortex$1");}
    public static final class Builder {
        private final Map<String,String> directives=new LinkedHashMap<>();
        private final boolean[] nearestDepth=new boolean[2],nearestColor=new boolean[2],mipmappedDepth=new boolean[2],mipmappedColor=new boolean[2];
        private final PackRenderTargets.Builder colors;
        private final java.util.Set<Integer> explicitClearColors=new java.util.HashSet<>();
        public Builder(ShaderPackProperties properties){colors=new PackRenderTargets.Builder(properties);}
        public void scan(String source){
            String text=source.replaceAll("(?m)//.*$","");var matcher=DIRECTIVE.matcher(text);
            while(matcher.find()){
                String name=matcher.group(2),value=matcher.group(3).trim();String expected=name.equals("shadowMapResolution")?"int":
                        name.contains("Filtering")||name.endsWith("Nearest")||name.contains("Mipmap")?"bool":"float";
                if(!matcher.group(1).equals(expected))throw invalid("Invalid shadow directive type: "+name);
                if(expected.equals("bool")&&!value.equals("true")&&!value.equals("false"))throw invalid("Expected literal shadow boolean: "+name);
                String prior=directives.putIfAbsent(name,value);if(prior!=null&&!prior.equals(value))throw invalid("Conflicting shadow directive: "+name);
                if(expected.equals("bool"))sampling(name,Boolean.parseBoolean(value));
            }
            // Keep only shadowcolor metadata. Main colortex declarations may coexist in the same source.
            var declarations=Pattern.compile("\\bconst\\s+(?:int|bool|vec4)\\s+shadow(?:color|Color)[01](?:Format|ClearColor|Clear|MipmapEnabled)\\s*=\\s*[^;]+;").matcher(text);
            while(declarations.find()){
                String declaration=declarations.group();colors.scan(remapColors(declaration));
                for(int index=0;index<2;index++)if(remapColors(declaration).contains("colortex"+index+"ClearColor"))explicitClearColors.add(index);
            }
        }
        private void sampling(String name,boolean value){
            // Iris dispatches directives in source order; aliases are assignments, never boolean ORs.
            if(name.equals("generateShadowMipmap")){java.util.Arrays.fill(mipmappedDepth,value);return;}
            if(name.equals("generateShadowColorMipmap")){java.util.Arrays.fill(mipmappedColor,value);return;}
            if(name.startsWith("shadowHardwareFiltering"))return; // Actual GLSL sampler type selects comparison.
            boolean color=name.startsWith("shadowcolor")||name.startsWith("shadowColor");
            int index=name.indexOf('1')>=0?1:0;
            (name.contains("Mipmap")?(color?mipmappedColor:mipmappedDepth):(color?nearestColor:nearestDepth))[index]=value;
        }
        public void use(ShaderPackProgram code,boolean writesShadowColors){
            if(writesShadowColors)for(int index:code.framebuffer().colorTargets())if(index>=0){if(index>1)throw invalid("Shadow output exceeds shadowcolor[0,1]");colors.requireTarget(index);}
            for(String name:code.descriptors().samplers().keySet()){int index=colorIndex(name);if(index>=0)colors.requireTarget(index);}
        }
        public PackShadowSettings build(){
            for(int index=0;index<2;index++)if(!explicitClearColors.contains(index))colors.scan("const vec4 colortex"+index+"ClearColor = vec4(1.0);");
            return new PackShadowSettings(this);
        }
        private int integer(String name,int fallback,int min,int max){
            try{int value=PackLiterals.integer(directives.getOrDefault(name,Integer.toString(fallback)));if(value<min||value>max)throw invalid("Shadow resolution exceeds ["+min+","+max+"]");return value;}
            catch(NumberFormatException failure){throw invalid("Expected literal shadow integer: "+name+" = "+directives.get(name));}
        }
        private float number(String name,float fallback){
            try{String raw=directives.getOrDefault(name,Float.toString(fallback));float value=PackLiterals.decimal(raw);if(!Float.isFinite(value))throw invalid("Nonfinite shadow directive: "+name);return value;}
            catch(NumberFormatException failure){throw invalid("Expected literal shadow float: "+name+" = "+directives.get(name));}
        }
    }
    private static UnsupportedShaderException invalid(String message){return new UnsupportedShaderException(message);}
}
