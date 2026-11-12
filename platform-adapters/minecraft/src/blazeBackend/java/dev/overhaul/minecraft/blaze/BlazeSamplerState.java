package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.SamplerSettings;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL13.GL_CLAMP_TO_BORDER;
import static org.lwjgl.opengl.GL14.*;

/** The sampling half of OpenGL texture state: a texture keeps one, and so does every sampler object. */
final class BlazeSamplerState {
    private static final int LOD_BIAS=34049,WRAP_R=32882,COMPARE_MODE=34892,COMPARE_FUNC=34893,COMPARE_REF_TO_TEXTURE=34894;
    /** Every Vulkan device allows at least this much bias; asking a sampler for more is invalid. */
    private static final float MAX_LOD_BIAS=2;
    private int min=GL_NEAREST_MIPMAP_LINEAR,mag=GL_LINEAR,wrapS=GL_REPEAT,wrapT=GL_REPEAT,wrapR=GL_REPEAT,compareMode=GL_NONE,compareFunc=GL_LEQUAL;
    private float minLod=-1000,maxLod=1000,lodBias;
    private SamplerSettings.Border border=SamplerSettings.Border.TRANSPARENT_BLACK;
    /** Extra bias for mipmapped textures while the world draws below the window's resolution; zero otherwise. */
    private static volatile float worldBias;

    /** Sets the bias every mipmapped texture adds from now on; render thread only. */
    static void worldBias(float bias){worldBias=Math.max(-MAX_LOD_BIAS,Math.min(0,bias));}
    static float worldBias(){return worldBias;}

    /** The most anisotropy the device allows, 1 where it has none; set once the device exists. */
    private static volatile float anisotropyLimit=1;
    private static float anisotropy=1;
    private static int anisotropyRevision=-1;

    static void anisotropyLimit(float limit){anisotropyLimit=Math.max(1,limit);anisotropyRevision=-1;}

    /** The anisotropy every mipmapped texture filters with, from the {@code anisotropy} setting, 1 to the device's most. */
    static float worldAnisotropy(){
        int now=BlazeOptimization.revision();
        if(now!=anisotropyRevision){
            float wanted;
            try{wanted=Float.parseFloat(BlazeOptimization.value("anisotropy","1"));}catch(NumberFormatException ignored){wanted=1;}
            anisotropy=Float.isFinite(wanted)?Math.max(1,Math.min(anisotropyLimit,wanted)):1;anisotropyRevision=now;
        }
        return anisotropy;
    }

    /** A render target's own view samples nearest and clamped. */
    static BlazeSamplerState target(){var state=new BlazeSamplerState();state.min=state.mag=GL_NEAREST;state.wrapS=state.wrapT=GL_CLAMP_TO_EDGE;return state;}

    /** Whether the name is sampler state; texture-only names such as the mip range are not. */
    static boolean owns(int name){
        return switch(name){case GL_TEXTURE_MIN_FILTER,GL_TEXTURE_MAG_FILTER,GL_TEXTURE_WRAP_S,GL_TEXTURE_WRAP_T,WRAP_R,
                GL_TEXTURE_MIN_LOD,GL_TEXTURE_MAX_LOD,LOD_BIAS,COMPARE_MODE,COMPARE_FUNC->true;default->false;};
    }

    /** Sets one parameter that {@link #owns} names and tells whether the sampler changed; a value GL refuses throws. */
    boolean set(int name,float value){
        if(!Float.isFinite(value))throw new IllegalArgumentException("Nonfinite sampler parameter");
        switch(name){
            case GL_TEXTURE_MIN_FILTER->{int selected=integer(value);if(selected!=GL_NEAREST&&selected!=GL_LINEAR&&(selected<GL_NEAREST_MIPMAP_NEAREST||selected>GL_LINEAR_MIPMAP_LINEAR))throw unsupported(name);if(min==selected)return false;min=selected;}
            case GL_TEXTURE_MAG_FILTER->{int selected=integer(value);if(selected!=GL_NEAREST&&selected!=GL_LINEAR)throw unsupported(name);if(mag==selected)return false;mag=selected;}
            case GL_TEXTURE_WRAP_S->{int selected=integer(value);address(selected);if(wrapS==selected)return false;wrapS=selected;}
            case GL_TEXTURE_WRAP_T->{int selected=integer(value);address(selected);if(wrapT==selected)return false;wrapT=selected;}
            case WRAP_R->{int selected=integer(value);address(selected);if(wrapR==selected)return false;wrapR=selected;}
            case GL_TEXTURE_MIN_LOD->{if(minLod==value)return false;minLod=value;}
            case GL_TEXTURE_MAX_LOD->{if(maxLod==value)return false;maxLod=value;}
            case LOD_BIAS->{float selected=Math.max(-MAX_LOD_BIAS,Math.min(MAX_LOD_BIAS,value));if(lodBias==selected)return false;lodBias=selected;}
            case COMPARE_MODE->{int selected=integer(value);if(selected!=GL_NONE&&selected!=COMPARE_REF_TO_TEXTURE)throw unsupported(name);if(compareMode==selected)return false;compareMode=selected;}
            case COMPARE_FUNC->{int selected=integer(value);comparison(selected);if(compareFunc==selected)return false;compareFunc=selected;}
            default->throw unsupported(name);
        }
        return true;
    }

    /**
     * Sets the border colour and tells whether the sampler changed. Vulkan core has three fixed border colours, so
     * another colour reads as the nearest of them by alpha and brightness.
     */
    boolean border(float r,float g,float b,float a){
        if(!Float.isFinite(r)||!Float.isFinite(g)||!Float.isFinite(b)||!Float.isFinite(a))throw new IllegalArgumentException("Nonfinite border colour");
        boolean opaque=a>=0.5f,white=(r+g+b)/3>=0.5f;
        var selected=!opaque?SamplerSettings.Border.TRANSPARENT_BLACK:white?SamplerSettings.Border.OPAQUE_WHITE:SamplerSettings.Border.OPAQUE_BLACK;
        boolean black=r==0&&g==0&&b==0,exact=a==0&&black||a==1&&(black||r==1&&g==1&&b==1);
        if(!exact)BlazeGlBoundary.reportOnce("border colour","Texture border colour ("+r+", "+g+", "+b+", "+a+") from "+BlazeGlBoundary.caller()+" has no Vulkan preset; it reads the nearest of transparent black, opaque black and opaque white");
        if(border==selected)return false;border=selected;return true;
    }

    boolean mipmapped(){return min!=GL_NEAREST&&min!=GL_LINEAR;}

    /** The native sampler for a view whose last mip level is {@code highestMip}; the LOD range stays inside it. */
    SamplerSettings settings(int highestMip){
        var filter=min==GL_LINEAR||min==GL_LINEAR_MIPMAP_NEAREST||min==GL_LINEAR_MIPMAP_LINEAR?SamplerSettings.Filter.LINEAR:SamplerSettings.Filter.NEAREST;
        float highest=mipmapped()?highestMip:0,low=Math.max(0,Math.min(highest,minLod)),high=Math.max(low,Math.min(highest,maxLod));
        return new SamplerSettings(filter,mag==GL_LINEAR?SamplerSettings.Filter.LINEAR:SamplerSettings.Filter.NEAREST,
                min==GL_NEAREST_MIPMAP_LINEAR||min==GL_LINEAR_MIPMAP_LINEAR?SamplerSettings.Mipmap.LINEAR:SamplerSettings.Mipmap.NEAREST,
                address(wrapS),address(wrapT),address(wrapR),low,high,mipmapped()?worldAnisotropy():1,compareMode==GL_NONE?SamplerSettings.Compare.NONE:comparison(compareFunc),
                mipmapped()?Math.max(-MAX_LOD_BIAS,lodBias+worldBias):lodBias,border);
    }

    private static SamplerSettings.Address address(int mode){return switch(mode){case GL_REPEAT->SamplerSettings.Address.REPEAT;case GL_CLAMP_TO_EDGE->SamplerSettings.Address.CLAMP;case GL_MIRRORED_REPEAT,34627->SamplerSettings.Address.MIRROR;case GL_CLAMP_TO_BORDER->SamplerSettings.Address.BORDER;case GL_CLAMP->SamplerSettings.Address.CLAMP;default->throw unsupported(mode);};}
    private static SamplerSettings.Compare comparison(int function){return switch(function){case GL_NEVER->SamplerSettings.Compare.NEVER;case GL_LESS->SamplerSettings.Compare.LESS;case GL_EQUAL->SamplerSettings.Compare.EQUAL;case GL_LEQUAL->SamplerSettings.Compare.LESS_OR_EQUAL;case GL_GREATER->SamplerSettings.Compare.GREATER;case GL_NOTEQUAL->SamplerSettings.Compare.NOT_EQUAL;case GL_GEQUAL->SamplerSettings.Compare.GREATER_OR_EQUAL;case GL_ALWAYS->SamplerSettings.Compare.ALWAYS;default->throw unsupported(function);};}
    static int integer(float value){if(value!=(int)value)throw new IllegalArgumentException("Expected integral texture parameter");return (int)value;}
    static UnsupportedOperationException unsupported(int name){return new UnsupportedOperationException("Unsupported owned texture parameter: "+name);}
}
