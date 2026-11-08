package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.vulkan.VK10.*;

/** Owns storage/sampling state or a borrowed render-target view, never both. */
final class BlazeTexture implements AutoCloseable {
    private static final int LOD_BIAS=34049,WRAP_R=32882,COMPARE_MODE=34892,COMPARE_FUNC=34893,COMPARE_REF_TO_TEXTURE=34894;
    /** Every Vulkan device allows at least this much bias; asking a sampler for more is invalid. */
    private static final float MAX_LOD_BIAS=2;
    private final GpuAllocator allocator;
    private final BlazeQueue queue;
    private final BlazeTransfers transfers;
    private final BlazeSamplers samplers;
    private GpuImage image;
    private GpuImageView view,borrowed;
    private GpuSampler sampler;
    private boolean[] defined;
    private int min=GL_NEAREST_MIPMAP_LINEAR,mag=GL_LINEAR,wrapS=GL_REPEAT,wrapT=GL_REPEAT,wrapR=GL_REPEAT,compareMode=GL_NONE,compareFunc=GL_LEQUAL;
    private int base,max=1000;
    private float minLod=-1000,maxLod=1000,lodBias;
    private boolean attached;
    private int viewType=VK_IMAGE_VIEW_TYPE_2D;
    int channels=4,internal=GL_RGBA;
    BlazeTexture(GpuAllocator allocator,BlazeQueue queue,BlazeTransfers transfers,BlazeSamplers samplers){this.allocator=allocator;this.queue=queue;this.transfers=transfers;this.samplers=samplers;}
    BlazeTexture(GpuAllocator allocator,BlazeQueue queue,BlazeTransfers transfers,BlazeSamplers samplers,GpuImageView borrowed){this(allocator,queue,transfers,samplers);this.borrowed=borrowed;min=mag=GL_NEAREST;wrapS=wrapT=GL_CLAMP_TO_EDGE;max=borrowed.mipCount()-1;if(borrowed.image().format().isDepth()){internal=36012;channels=1;}}
    boolean borrowed(){return borrowed!=null;}
    GpuImage image(){return borrowed!=null?borrowed.image():java.util.Objects.requireNonNull(image,"Texture has no allocated image");}
    int mip(int relative){return Math.addExact(relative,borrowed==null?0:borrowed.baseMip());}
    int layer(){return borrowed==null?0:borrowed.baseLayer();}
    boolean defined(int level){return level>=0&&(borrowed!=null?level<borrowed.mipCount():defined!=null&&level<defined.length&&defined[level]);}
    void define(int level){if(borrowed!=null||defined==null||level<0||level>=defined.length)throw new IllegalArgumentException("Undefined texture mip allocation");defined[level]=true;}
    /** The levels a mipmap generation just wrote. */
    void defineMipmaps(int first){for(int level=first;defined!=null&&level<defined.length;level++)defined[level]=true;}
    int baseLevel(){return base;}
    boolean complete(){
        if(!defined(base)||max<base)return false;var source=image();int first=mip(base);
        boolean mipmapped=min!=GL_NEAREST&&min!=GL_LINEAR;
        // A volume holds one level, so its mipmapped filters read that level as GL reads a texture limited to it.
        int last=mipmapped&&!source.volume()?Math.min(max,base+31-Integer.numberOfLeadingZeros(Math.max(source.width(first),source.height(first)))):base;
        for(int level=base;level<=last;level++)if(!defined(level))return false;return true;
    }
    void allocate(int internal,int width,int height,int levels,ImmediateCommands commands){
        allocate(internal,width,height,levels,commands,true);
    }
    void allocate(int internal,int width,int height,int levels,ImmediateCommands commands,boolean defineAll){
        allocate(internal,width,height,levels,1,VK_IMAGE_VIEW_TYPE_2D,commands,defineAll);
    }
    /** Storage of a 2D, cube or 2D array texture: a cube holds its six faces as layers. */
    void allocate(int internal,int width,int height,int levels,int layers,int viewType,ImmediateCommands commands,boolean defineAll){
        if(borrowed())throw new UnsupportedOperationException("Cannot redefine a render-target texture alias");
        if(layers<1||viewType==VK_IMAGE_VIEW_TYPE_CUBE&&(layers!=6||width!=height))throw new IllegalArgumentException("Invalid layered texture shape");
        var storage=BlazeTextureFormats.storage(internal);int count=storage.channels();ImageFormat format=storage.format();
        // A 3D texture's depth is its slices; volumes here hold one mip level.
        boolean volume=viewType==VK_IMAGE_VIEW_TYPE_3D;if(volume)levels=1;
        GpuImage next=volume?allocator.createVolume(format,width,height,layers,usage(format)):allocator.createImage(format,width,height,levels,layers,usage(format),flags(viewType));
        try{
            if(!format.isDepth())for(int level=0;level<levels;level++)for(int layer=0;layer<next.layers();layer++){
                if(transfers!=null)transfers.clearColor(next,level,layer,0,0,0,count==4?0:1);
                else commands.clearColor(next,level,layer,0,0,0,count==4?0:1);
            }
        }catch(RuntimeException|Error failure){retire(next);throw failure;}
        try{releaseSampling();if(image!=null)retire(image);}
        catch(RuntimeException|Error failure){try{retire(next);}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
        image=next;this.internal=internal;channels=count;defined=new boolean[levels];this.viewType=viewType;
        if(defineAll)java.util.Arrays.fill(defined,true);else defined[0]=true;
    }
    /**
     * Renderbuffer storage holding several samples per pixel. Nothing samples a renderbuffer, so the image is only an
     * attachment and a transfer endpoint; {@code samples} is a {@code VK_SAMPLE_COUNT_*_BIT} the device supports.
     */
    void allocateMultisampled(int internal,int width,int height,int samples){
        if(borrowed())throw new UnsupportedOperationException("Cannot redefine a render-target texture alias");
        var storage=BlazeTextureFormats.storage(internal);ImageFormat format=storage.format();attached=true;
        GpuImage next=allocator.createMultisampled(format,width,height,samples,multisampledUsage(format));
        try{releaseSampling();if(image!=null)retire(image);}
        catch(RuntimeException|Error failure){try{retire(next);}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
        image=next;this.internal=internal;channels=storage.channels();defined=new boolean[]{true};viewType=VK_IMAGE_VIEW_TYPE_2D;
    }
    static int multisampledUsage(ImageFormat format){
        return VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT
                |(format.isDepth()?VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT:VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
    }
    /** The view dimension this texture samples as; a render-target alias is always 2D. */
    int viewType(){return borrowed!=null?VK_IMAGE_VIEW_TYPE_2D:viewType;}
    /** Layers of a cube or array, slices of a 3D texture. */
    int layers(){return borrowed!=null?1:image==null?0:image.volume()?image.depth():image.layers();}
    private static int flags(int viewType){return viewType==VK_IMAGE_VIEW_TYPE_CUBE?VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT:0;}
    private int usage(ImageFormat format){
        int usage=VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        return attached?usage|(format.isDepth()?VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT:VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT):usage;
    }
    /** Marks this texture as a framebuffer attachment: its image moves to one that allows it, keeping any colour contents. */
    void attach(){
        if(borrowed()||attached)return;
        if(viewType==VK_IMAGE_VIEW_TYPE_3D){BlazeGlBoundary.reportOnce("3D attachment","A mod attached a 3D texture to a framebuffer; it receives no output");return;}
        attached=true;if(image==null)return;
        GpuImage next=allocator.createImage(image.format(),image.width(0),image.height(0),image.mipLevels(),image.layers(),usage(image.format()),flags(viewType));
        if(transfers!=null&&!image.format().isDepth())for(int level=0;level<image.mipLevels();level++)for(int layer=0;layer<image.layers();layer++){
            var area=new ImageRegion(level,layer,0,0,image.width(level),image.height(level));transfers.copy(image,area,next,area);
        }
        releaseSampling();retire(image);image=next;
    }
    int maximum(){return max;}
    void parameter(int name,float value){
        if(!Float.isFinite(value))throw new IllegalArgumentException("Nonfinite texture parameter");
        switch(name){
            case GL_TEXTURE_MIN_FILTER->{int selected=integer(value);if(selected!=GL_NEAREST&&selected!=GL_LINEAR&&(selected<GL_NEAREST_MIPMAP_NEAREST||selected>GL_LINEAR_MIPMAP_LINEAR))throw unsupported(name);if(min==selected)return;min=selected;}
            case GL_TEXTURE_MAG_FILTER->{int selected=integer(value);if(selected!=GL_NEAREST&&selected!=GL_LINEAR)throw unsupported(name);if(mag==selected)return;mag=selected;}
            case GL_TEXTURE_WRAP_S->{int selected=integer(value);address(selected);if(wrapS==selected)return;wrapS=selected;}
            case GL_TEXTURE_WRAP_T->{int selected=integer(value);address(selected);if(wrapT==selected)return;wrapT=selected;}
            case GL_TEXTURE_BASE_LEVEL->{if(value<0)throw unsupported(name);int selected=integer(value);if(base==selected)return;base=selected;releaseSampling();return;}
            case GL_TEXTURE_MAX_LEVEL->{if(value<0)throw unsupported(name);int selected=integer(value);if(max==selected)return;max=selected;releaseSampling();return;}
            case GL_TEXTURE_MIN_LOD->{if(minLod==value)return;minLod=value;}
            case GL_TEXTURE_MAX_LOD->{if(maxLod==value)return;maxLod=value;}
            case WRAP_R->{int selected=integer(value);address(selected);if(wrapR==selected)return;wrapR=selected;}
            case LOD_BIAS->{float selected=Math.max(-MAX_LOD_BIAS,Math.min(MAX_LOD_BIAS,value));if(lodBias==selected)return;lodBias=selected;}
            case COMPARE_MODE->{int selected=integer(value);if(selected!=GL_NONE&&selected!=COMPARE_REF_TO_TEXTURE)throw unsupported(name);if(compareMode==selected)return;compareMode=selected;}
            case COMPARE_FUNC->{int selected=integer(value);comparison(selected);if(compareFunc==selected)return;compareFunc=selected;}
            default->{BlazeGlBoundary.reportOnce("parameter "+name,"Texture parameter has no Vulkan sampler state and was ignored: "+name);return;}
        }
        // Only the mip range shapes the view; everything else here is sampler state.
        sampler=null;
    }
    GpuImageView view(){
        if(view==null){
            GpuImage source=image();int available=borrowed==null?source.mipLevels():borrowed.mipCount();
            if(base>=available||max<base)throw new IllegalStateException("Incomplete texture mip range");
            int count=Math.min(available-1,max)-base+1;
            view=borrowed!=null?source.view(mip(base),count,layer(),1):source.view(mip(base),count,0,source.layers(),viewType,source.format().aspectMask());
            BlazeStats.viewCreated();
        }
        return view;
    }
    GpuSampler sampler(){
        if(sampler==null){
            var filter=min==GL_LINEAR||min==GL_LINEAR_MIPMAP_NEAREST||min==GL_LINEAR_MIPMAP_LINEAR?SamplerSettings.Filter.LINEAR:SamplerSettings.Filter.NEAREST;
            boolean mipmapped=min!=GL_NEAREST&&min!=GL_LINEAR;
            float highest=mipmapped?view().mipCount()-1:0,low=Math.max(0,Math.min(highest,minLod)),high=Math.max(low,Math.min(highest,maxLod));
            sampler=samplers.get(new SamplerSettings(filter,mag==GL_LINEAR?SamplerSettings.Filter.LINEAR:SamplerSettings.Filter.NEAREST,
                    min==GL_NEAREST_MIPMAP_LINEAR||min==GL_LINEAR_MIPMAP_LINEAR?SamplerSettings.Mipmap.LINEAR:SamplerSettings.Mipmap.NEAREST,
                    address(wrapS),address(wrapT),address(wrapR),low,high,1,compareMode==GL_NONE?SamplerSettings.Compare.NONE:comparison(compareFunc),lodBias));
        }
        return sampler;
    }
    private static SamplerSettings.Address address(int mode){return switch(mode){case GL_REPEAT->SamplerSettings.Address.REPEAT;case GL_CLAMP_TO_EDGE->SamplerSettings.Address.CLAMP;case GL_MIRRORED_REPEAT,34627->SamplerSettings.Address.MIRROR;case 33069,10496->SamplerSettings.Address.CLAMP;default->throw unsupported(mode);};}
    private static SamplerSettings.Compare comparison(int function){return switch(function){case GL_NEVER->SamplerSettings.Compare.NEVER;case GL_LESS->SamplerSettings.Compare.LESS;case GL_EQUAL->SamplerSettings.Compare.EQUAL;case GL_LEQUAL->SamplerSettings.Compare.LESS_OR_EQUAL;case GL_GREATER->SamplerSettings.Compare.GREATER;case GL_NOTEQUAL->SamplerSettings.Compare.NOT_EQUAL;case GL_GEQUAL->SamplerSettings.Compare.GREATER_OR_EQUAL;case GL_ALWAYS->SamplerSettings.Compare.ALWAYS;default->throw unsupported(function);};}
    private static int integer(float value){if(value!=(int)value)throw new IllegalArgumentException("Expected integral texture parameter");return (int)value;}
    private static UnsupportedOperationException unsupported(int name){return new UnsupportedOperationException("Unsupported owned texture parameter: "+name);}
    private void retire(AutoCloseable resource){if(queue==null)BlazeQueue.closeResource(resource);else queue.retire(resource);}
    private void releaseSampling(){sampler=null;if(view!=null){GpuImageView old=view;view=null;retire(old);}}
    @Override public void close(){try{releaseSampling();}finally{if(image!=null){GpuImage old=image;image=null;retire(old);}borrowed=null;}}
}
