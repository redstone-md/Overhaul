package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.vulkan.VK10.*;

/** Owns storage/sampling state or a borrowed render-target view, never both. */
final class BlazeTexture implements AutoCloseable {
    private final GpuAllocator allocator;
    private final BlazeQueue queue;
    private final BlazeTransfers transfers;
    private GpuImage image;
    private GpuImageView view,borrowed;
    private GpuSampler sampler;
    private boolean[] defined;
    private int min=GL_NEAREST_MIPMAP_LINEAR,mag=GL_LINEAR,wrapS=GL_REPEAT,wrapT=GL_REPEAT;
    private int base,max=1000;
    private float minLod=-1000,maxLod=1000;
    int channels=4,internal=GL_RGBA;
    BlazeTexture(GpuAllocator allocator,BlazeQueue queue,BlazeTransfers transfers){this.allocator=allocator;this.queue=queue;this.transfers=transfers;}
    BlazeTexture(GpuAllocator allocator,BlazeQueue queue,BlazeTransfers transfers,GpuImageView borrowed){this(allocator,queue,transfers);this.borrowed=borrowed;min=mag=GL_NEAREST;wrapS=wrapT=GL_CLAMP_TO_EDGE;max=borrowed.mipCount()-1;if(borrowed.image().format().isDepth()){internal=36012;channels=1;}}
    boolean borrowed(){return borrowed!=null;}
    GpuImage image(){return borrowed!=null?borrowed.image():java.util.Objects.requireNonNull(image,"Texture has no allocated image");}
    int mip(int relative){return Math.addExact(relative,borrowed==null?0:borrowed.baseMip());}
    int layer(){return borrowed==null?0:borrowed.baseLayer();}
    boolean defined(int level){return level>=0&&(borrowed!=null?level<borrowed.mipCount():defined!=null&&level<defined.length&&defined[level]);}
    void define(int level){if(borrowed!=null||defined==null||level<0||level>=defined.length)throw new IllegalArgumentException("Undefined texture mip allocation");defined[level]=true;}
    boolean complete(){
        if(!defined(base)||max<base)return false;var source=image();int first=mip(base);
        boolean mipmapped=min!=GL_NEAREST&&min!=GL_LINEAR;
        int last=mipmapped?Math.min(max,base+31-Integer.numberOfLeadingZeros(Math.max(source.width(first),source.height(first)))):base;
        for(int level=base;level<=last;level++)if(!defined(level))return false;return true;
    }
    void allocate(int internal,int width,int height,int levels,ImmediateCommands commands){
        allocate(internal,width,height,levels,commands,true);
    }
    void allocate(int internal,int width,int height,int levels,ImmediateCommands commands,boolean defineAll){
        if(borrowed())throw new UnsupportedOperationException("Cannot redefine a render-target texture alias");
        var storage=BlazeTextureFormats.storage(internal);int count=storage.channels();ImageFormat format=storage.format();
        GpuImage next=allocator.createImage(format,width,height,levels,1,
                VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        try{
            if(!format.isDepth())for(int level=0;level<levels;level++){
                if(transfers!=null)transfers.clearColor(next,level,0,0,0,0,count==4?0:1);
                else commands.clearColor(next,level,0,0,0,0,count==4?0:1);
            }
        }catch(RuntimeException|Error failure){retire(next);throw failure;}
        try{releaseSampling();if(image!=null)retire(image);}
        catch(RuntimeException|Error failure){try{retire(next);}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
        image=next;this.internal=internal;channels=count;defined=new boolean[levels];
        if(defineAll)java.util.Arrays.fill(defined,true);else defined[0]=true;
    }
    int maximum(){return max;}
    void parameter(int name,float value){
        if(!Float.isFinite(value))throw new IllegalArgumentException("Nonfinite texture parameter");
        switch(name){
            case GL_TEXTURE_MIN_FILTER->{int selected=integer(value);if(selected!=GL_NEAREST&&selected!=GL_LINEAR&&(selected<GL_NEAREST_MIPMAP_NEAREST||selected>GL_LINEAR_MIPMAP_LINEAR))throw unsupported(name);if(min==selected)return;min=selected;}
            case GL_TEXTURE_MAG_FILTER->{int selected=integer(value);if(selected!=GL_NEAREST&&selected!=GL_LINEAR)throw unsupported(name);if(mag==selected)return;mag=selected;}
            case GL_TEXTURE_WRAP_S->{int selected=integer(value);address(selected);if(wrapS==selected)return;wrapS=selected;}
            case GL_TEXTURE_WRAP_T->{int selected=integer(value);address(selected);if(wrapT==selected)return;wrapT=selected;}
            case GL_TEXTURE_BASE_LEVEL->{if(value<0)throw unsupported(name);int selected=integer(value);if(base==selected)return;base=selected;}
            case GL_TEXTURE_MAX_LEVEL->{if(value<0)throw unsupported(name);int selected=integer(value);if(max==selected)return;max=selected;}
            case GL_TEXTURE_MIN_LOD->{if(minLod==value)return;minLod=value;}
            case GL_TEXTURE_MAX_LOD->{if(maxLod==value)return;maxLod=value;}
            default->{BlazeGlBoundary.reportOnce("parameter "+name,"Texture parameter has no Vulkan sampler state and was ignored: "+name);return;}
        }
        releaseSampling();
    }
    GpuImageView view(){
        if(view==null){
            GpuImage source=image();int available=borrowed==null?source.mipLevels():borrowed.mipCount();
            if(base>=available||max<base)throw new IllegalStateException("Incomplete texture mip range");
            int count=Math.min(available-1,max)-base+1;
            view=source.view(mip(base),count,layer(),1);
        }
        return view;
    }
    GpuSampler sampler(){
        if(sampler==null){
            var filter=min==GL_LINEAR||min==GL_LINEAR_MIPMAP_NEAREST||min==GL_LINEAR_MIPMAP_LINEAR?SamplerSettings.Filter.LINEAR:SamplerSettings.Filter.NEAREST;
            boolean mipmapped=min!=GL_NEAREST&&min!=GL_LINEAR;
            float highest=mipmapped?view().mipCount()-1:0,low=Math.max(0,Math.min(highest,minLod)),high=Math.max(low,Math.min(highest,maxLod));
            sampler=allocator.createSampler(new SamplerSettings(filter,mag==GL_LINEAR?SamplerSettings.Filter.LINEAR:SamplerSettings.Filter.NEAREST,
                    min==GL_NEAREST_MIPMAP_LINEAR||min==GL_LINEAR_MIPMAP_LINEAR?SamplerSettings.Mipmap.LINEAR:SamplerSettings.Mipmap.NEAREST,
                    address(wrapS),address(wrapT),SamplerSettings.Address.CLAMP,low,high,1,SamplerSettings.Compare.NONE));
        }
        return sampler;
    }
    private static SamplerSettings.Address address(int mode){return switch(mode){case GL_REPEAT->SamplerSettings.Address.REPEAT;case GL_CLAMP_TO_EDGE->SamplerSettings.Address.CLAMP;case GL_MIRRORED_REPEAT,34627->SamplerSettings.Address.MIRROR;case 33069,10496->SamplerSettings.Address.CLAMP;default->throw unsupported(mode);};}
    private static int integer(float value){if(value!=(int)value)throw new IllegalArgumentException("Expected integral texture parameter");return (int)value;}
    private static UnsupportedOperationException unsupported(int name){return new UnsupportedOperationException("Unsupported owned texture parameter: "+name);}
    private void retire(AutoCloseable resource){if(queue==null)BlazeQueue.closeResource(resource);else queue.retire(resource);}
    private void releaseSampling(){if(sampler!=null){GpuSampler old=sampler;sampler=null;retire(old);}if(view!=null){GpuImageView old=view;view=null;retire(old);}}
    @Override public void close(){try{releaseSampling();}finally{if(image!=null){GpuImage old=image;image=null;retire(old);}borrowed=null;}}
}
