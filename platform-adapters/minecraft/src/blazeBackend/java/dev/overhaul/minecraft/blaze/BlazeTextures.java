package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.VulkanStack;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.overhaul.vulkan.*;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageFormatProperties;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.vulkan.VK10.*;

/** Device-owned texture namespace, upload pixel-store state and deterministic resource closure. */
public final class BlazeTextures implements AutoCloseable {
    /**
     * A bound image; {@code general} marks a pack storage image that stays in GENERAL layout and is never transitioned.
     * A pack storage buffer binds through {@code buffer} and has no view.
     */
    public record Sampled(GpuImageView view,long sampler,boolean general,GpuBuffer buffer){
        public Sampled(GpuImageView view,long sampler,boolean general){this(view,sampler,general,null);}
        public Sampled(GpuImageView view,long sampler){this(view,sampler,false,null);}
        public static Sampled storage(GpuBuffer buffer){return new Sampled(null,0,true,java.util.Objects.requireNonNull(buffer));}
        int layout(){return general?VK_IMAGE_LAYOUT_GENERAL:VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;}
    }
    /** One subresource of a texture that a framebuffer renders to. */
    record Attachment(GpuImage image,int mip,int layer){}
    private final VulkanContext context;
    private final GpuAllocator allocator;
    private final ImmediateCommands commands;
    private final BlazeQueue queue;
    private final BlazeTransfers transfers;
    private final BlazeSamplers samplers;
    /** By name: names are handed out in sequence and never reused, and this is read several times a draw. */
    private BlazeTexture[] textures=new BlazeTexture[1024];
    private final BlazePixelStore pixels=new BlazePixelStore();
    private final int[] bindings=new int[32];
    private BlazeTexture incomplete;
    private int next=1,active;
    private int proxyWidth,proxyHeight,proxyInternal;
    private boolean closed;
    public BlazeTextures(VulkanContext context,GpuAllocator allocator,ImmediateCommands commands){
        this(context,allocator,commands,null,null);
    }
    BlazeTextures(VulkanContext context,GpuAllocator allocator,ImmediateCommands commands,BlazeQueue queue,BlazeTransfers transfers){
        this.context=context;this.allocator=allocator;this.commands=commands;
        this.queue=queue;this.transfers=transfers;samplers=new BlazeSamplers(allocator,queue);
        if(allocator.context()!=context||commands.context()!=context)throw new IllegalArgumentException("Texture dependencies belong to different devices");
        textures[0]=new BlazeTexture(allocator,queue,transfers,samplers);
    }
    public int generate(){requireOpen();int id=nextId();store(id,new BlazeTexture(allocator,queue,transfers,samplers));return id;}
    public int borrow(GpuImageView view){
        requireOpen();if(view.image().context()!=context||view.layerCount()!=1)throw new IllegalArgumentException("Foreign or layered borrowed texture");
        int id=nextId();store(id,new BlazeTexture(allocator,queue,transfers,samplers,view));return id;
    }
    /** The renderable storage behind a name, or null while the name holds none at that level. */
    Attachment attachment(int id,int level){
        requireOpen();BlazeTexture texture=find(id);if(texture==null||!texture.defined(level))return null;
        texture.attach();return new Attachment(texture.image(),texture.mip(level),texture.layer());
    }
    void renderbufferStorage(int id,int internal,int width,int height){
        requireOpen();var texture=texture(id);texture.attach();texture.allocate(internal,width,height,1,commands);
    }
    /** Immutable storage: the same image as a full mip chain defined level by level. */
    void storage(int target,int levels,int internal,int width,int height){target(target);current().allocate(internal,width,height,levels,commands);}
    private int nextId(){if(next<=0)throw new IllegalStateException("Texture namespace exhausted");return next++;}
    public void delete(int id){
        requireOpen();if(id==0)return;BlazeTexture texture=find(id);if(texture==null)return;textures[id]=null;
        for(int unit=0;unit<bindings.length;unit++)if(bindings[unit]==id)bindings[unit]=0;texture.close();
    }
    public void active(int textureUnit){
        requireOpen();int unit=textureUnit-GL_TEXTURE0;if(unit<0||unit>=bindings.length)throw new IllegalArgumentException("Texture unit outside owned range");active=unit;
    }
    public int active(){requireOpen();return GL_TEXTURE0+active;}
    public int textureUnits(){requireOpen();return bindings.length;}
    public int boundId(int unit){requireOpen();if(unit<0||unit>=bindings.length)throw new IllegalArgumentException("Texture unit outside owned range");return bindings[unit];}
    public void bind(int id){requireOpen();if(id!=0&&find(id)==null)throw new IllegalArgumentException("Unknown owned texture: "+id);bindings[active]=id;}
    public Integer integer(int name){
        requireOpen();if(name==GL_ACTIVE_TEXTURE)return GL_TEXTURE0+active;if(name==GL_TEXTURE_BINDING_2D)return bindings[active];return pixels.integer(name);
    }
    public void pixelStore(int name,int value){requireOpen();pixels.set(name,value);}
    public void parameter(int target,int name,float value){target(target);current().parameter(name,value);}
    public void prepare(int internal,int id,int maximumMip,int width,int height){
        requireOpen();if(maximumMip<0)throw new IllegalArgumentException("Negative texture mip count");bind(id);
        var texture=current();texture.parameter(GL_TEXTURE_MAX_LEVEL,maximumMip);texture.parameter(GL_TEXTURE_MIN_LOD,0);texture.parameter(GL_TEXTURE_MAX_LOD,maximumMip);
        texture.allocate(internal,width,height,Math.addExact(maximumMip,1),commands);
    }
    public void image(int target,int level,int internal,int width,int height,int border,int format,int type,IntBuffer input){
        requireOpen();if(target==GL_PROXY_TEXTURE_2D){proxy(level,internal,width,height,border,format,type,input);return;}
        target(target);if(border!=0||level<0)throw new UnsupportedOperationException("Texture borders/negative levels are unsupported");
        BlazePixels.bytes(format,type);
        var texture=current();
        if(level==0){
            int physical=32-Integer.numberOfLeadingZeros(Math.max(width,height));
            texture.allocate(internal,width,height,Math.addExact(Math.min(physical-1,texture.maximum()),1),commands,false);
        }else{
            var image=texture.image();if(texture.mip(level)>=image.mipLevels()||image.width(texture.mip(level))!=width||image.height(texture.mip(level))!=height||texture.internal!=internal)
                throw new UnsupportedOperationException("Noncanonical texture mip redefinition");
            if(!image.format().isDepth())clearColor(image,texture.mip(level),texture.layer(),0,0,0,texture.channels==4?0:1);
            texture.define(level);
        }
        if(input!=null)upload(target,level,0,0,width,height,format,type,MemoryUtil.memByteBuffer(input));
    }
    public void upload(int target,int level,int x,int y,int width,int height,int format,int type,long address){
        requireOpen();target(target);if(width==0||height==0)return;
        if(address==0)throw new IllegalArgumentException("Null pixel source");
        var layout=pixels.layout(false,width,height,BlazePixels.bytes(format,type));
        upload(target,level,x,y,width,height,format,type,MemoryUtil.memByteBuffer(address,layout.bytes()));
    }
    public void upload(int target,int level,int x,int y,int width,int height,int format,int type,ByteBuffer source){
        target(target);if(width==0||height==0)return;var texture=current();
        var area=region(texture,level,x,y,width,height);var layout=pixels.layout(false,width,height,BlazePixels.bytes(format,type));
        if(source.remaining()<layout.bytes())throw new IllegalArgumentException("Pixel source is truncated");
        int storage=texture.image().format().bytesPerPixel();
        if(!BlazeTextureFormats.storage(texture.internal).convertible()||!BlazePixels.convertible(format,type)){
            if(texture.image().format().isDepth()||BlazePixels.bytes(format,type)!=storage){
                BlazeGlBoundary.reportOnce("upload "+texture.internal+" "+format+" "+type,"Texture upload with no conversion was skipped: internal "+texture.internal+", format "+format+", type "+type);return;
            }
            uploadImage(texture.image(),area,source.slice(source.position()+layout.offset(),layout.bytes()-layout.offset()),layout.stride());return;
        }
        if(type==GL_UNSIGNED_BYTE&&texture.channels==storage&&BlazePixels.components(format)==storage&&format!=GL_BGRA&&format!=GL_BGR&&layout.stride()>=(long)width*storage){
            uploadImage(texture.image(),area,source.slice(source.position()+layout.offset(),layout.bytes()-layout.offset()),layout.stride());return;
        }
        ByteBuffer converted=BlazePixels.unpack(source,layout,width,height,format,type,texture.channels,storage);
        try{uploadImage(texture.image(),area,converted,0);}finally{MemoryUtil.memFree(converted);}
    }
    public void download(int target,int level,int format,int type,long address){
        target(target);var texture=current();if(!texture.defined(level))throw new IllegalStateException("Texture mip is not defined");var image=texture.image();int mip=texture.mip(level);
        transfer(image,new ImageRegion(mip,texture.layer(),0,0,image.width(mip),image.height(mip)),format,type,address);
    }
    public void read(BlazeTarget target,int x,int y,int width,int height,int format,int type,long address){
        requireOpen();if(width==0||height==0||target==null)return;
        var image=readImage(target,format);transfer(image,new ImageRegion(target.level(),0,x,y,width,height),format,type,address);
    }
    public void read(BlazeTarget target,int x,int y,int width,int height,int format,int type,ByteBuffer destination){
        requireOpen();if(width==0||height==0||target==null)return;
        var image=readImage(target,format);transfer(image,new ImageRegion(target.level(),0,x,y,width,height),format,type,destination);
    }
    private GpuImage readImage(BlazeTarget target,int format){
        if(target==null)throw new IllegalStateException("No read framebuffer is bound");
        GpuImage image=format==GL_DEPTH_COMPONENT?target.depth():target.color();if(image==null)throw new IllegalStateException("Read framebuffer has no depth attachment");return image;
    }
    private void transfer(GpuImage image,ImageRegion region,int format,int type,long address){
        if(address==0)throw new IllegalArgumentException("Null pixel destination");
        var layout=pixels.layout(true,region.width(),region.height(),BlazePixels.bytes(format,type));
        transfer(image,region,format,type,MemoryUtil.memByteBuffer(address,layout.bytes()));
    }
    private void transfer(GpuImage image,ImageRegion region,int format,int type,ByteBuffer destination){
        if(image.format().isDepth()!=(format==GL_DEPTH_COMPONENT))throw new UnsupportedOperationException("Pixel transfer aspect does not match source image");
        var layout=pixels.layout(true,region.width(),region.height(),BlazePixels.bytes(format,type));
        var bytes=immediate().readback(image,region,0);BlazePixels.pack(bytes,image.format().isDepth(),image.format().bytesPerPixel(),destination,layout,region.width(),region.height(),format,type);
    }
    public void copy(BlazeTarget source,int target,int level,int xOffset,int yOffset,int x,int y,int width,int height){
        target(target);if(width==0||height==0||source==null)return;var texture=current();
        var from=readImage(source,GL_RGBA);var to=texture.image();var destination=region(texture,level,xOffset,yOffset,width,height);
        if(to.format().isDepth()||from==to)throw new UnsupportedOperationException("Framebuffer color copy cannot target depth or its own source image");
        var area=new ImageRegion(source.level(),0,x,y,width,height);
        if(from.format()==to.format()&&texture.channels==4){if(transfers!=null)transfers.copy(from,area,to,destination);else commands.copyImage(from,area,to,destination);return;}
        var bytes=immediate().readback(from,area,0);int stride=Math.multiplyExact(width,4),size=Math.multiplyExact(stride,height);
        var layout=new BlazePixelStore.Layout(stride,0,size);
        var converted=BlazePixels.unpack(bytes,layout,width,height,GL_RGBA,GL_UNSIGNED_BYTE,texture.channels,to.format().bytesPerPixel());
        try{uploadImage(to,destination,converted,0);}finally{MemoryUtil.memFree(converted);}
    }
    public int levelParameter(int target,int level,int name){
        requireOpen();
        if(name!=GL_TEXTURE_WIDTH&&name!=GL_TEXTURE_HEIGHT&&name!=GL_TEXTURE_INTERNAL_FORMAT)throw new UnsupportedOperationException("Unsupported texture level query: "+name);
        if(target==GL_PROXY_TEXTURE_2D){if(level!=0)return 0;return switch(name){case GL_TEXTURE_WIDTH->proxyWidth;case GL_TEXTURE_HEIGHT->proxyHeight;case GL_TEXTURE_INTERNAL_FORMAT->proxyInternal;default->throw new AssertionError();};}
        target(target);var texture=current();if(!texture.defined(level))return 0;var image=texture.image();int mip=texture.mip(level);
        return switch(name){case GL_TEXTURE_WIDTH->image.width(mip);case GL_TEXTURE_HEIGHT->image.height(mip);case GL_TEXTURE_INTERNAL_FORMAT->texture.internal;
            default->throw new UnsupportedOperationException("Unsupported texture level query: "+name);};
    }
    public Sampled resolve(Object value){
        requireOpen();int id;
        if(value instanceof Sampled ready)return ready;
        if(value instanceof Integer number)id=number;
        else if(value instanceof java.util.function.IntSupplier supplier)id=supplier.getAsInt();
        else if(value instanceof AbstractTexture texture)id=texture.getId();
        else if(value instanceof RenderTarget target)id=target.getColorTextureId();
        else throw new UnsupportedOperationException("Unsupported texture sampler payload: "+(value==null?"null":value.getClass().getName()));
        return sampled(id);
    }
    public void generateMipmap(int target){
        requireOpen();target(target);var texture=current();
        if(texture.borrowed()||!texture.defined(texture.baseLevel()))return;
        transfers.mipmaps(texture.image(),texture.baseLevel(),0);texture.defineMipmaps(texture.baseLevel()+1);
    }
    public Sampled resolveBound(int unit){return sampled(boundId(unit));}
    private Sampled sampled(int id){
        var texture=texture(id);if(!texture.complete())texture=incomplete();var view=texture.view();
        if(queue==null)for(int mip=view.baseMip();mip<view.baseMip()+view.mipCount();mip++)if(view.image().layout(mip,view.baseLayer())!=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
            commands.transition(view.image(),mip,view.baseLayer(),VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        return new Sampled(view,texture.sampler().handle());
    }
    private BlazeTexture current(){requireOpen();return texture(bindings[active]);}
    private BlazeTexture incomplete(){
        if(incomplete==null){
            var created=new BlazeTexture(allocator,queue,transfers,samplers);
            try{created.allocate(GL_RGBA,1,1,1,commands);clearColor(created.image(),0,0,0,0,0,1);incomplete=created;}
            catch(RuntimeException|Error failure){try{created.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
        }
        return incomplete;
    }
    private ImageRegion region(BlazeTexture texture,int level,int x,int y,int width,int height){
        if(!texture.defined(level))throw new IllegalStateException("Texture mip is not defined");
        var image=texture.image();int mip=texture.mip(level);
        if(x<0||y<0||width<1||height<1||x>image.width(mip)-width||y>image.height(mip)-height)throw new IllegalArgumentException("Pixel rectangle exceeds texture mip");
        return new ImageRegion(mip,texture.layer(),x,y,width,height);
    }
    private void proxy(int level,int internal,int width,int height,int border,int format,int type,IntBuffer input){
        if(level!=0||border!=0||input!=null||internal!=GL_RGBA||format!=GL_RGBA||type!=GL_UNSIGNED_BYTE)
            throw new UnsupportedOperationException("Unsupported proxy texture capability query");
        proxyWidth=proxyHeight=proxyInternal=0;if(width<1||height<1)return;
        try(MemoryStack stack=VulkanStack.push()){
            var support=VkImageFormatProperties.malloc(stack);
            int result=vkGetPhysicalDeviceImageFormatProperties(context.physicalDevice(),ImageFormat.RGBA8_UNORM.vkFormat(),VK_IMAGE_TYPE_2D,VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT,0,support);
            if(result==VK_ERROR_FORMAT_NOT_SUPPORTED)return;dev.overhaul.vulkan.VulkanException.check(result,"Owned proxy texture format capabilities");
            if(width<=support.maxExtent().width()&&height<=support.maxExtent().height()){proxyWidth=width;proxyHeight=height;proxyInternal=internal;}
        }
    }
    private BlazeTexture find(int id){return id>=0&&id<textures.length?textures[id]:null;}
    private void store(int id,BlazeTexture texture){if(id>=textures.length)textures=Arrays.copyOf(textures,id*2);textures[id]=texture;}
    private BlazeTexture texture(int id){var texture=find(id);if(texture==null)throw new IllegalStateException("No allocated owned texture is bound: "+id);return texture;}
    private ImmediateCommands immediate(){if(queue!=null)queue.flush();return commands;}
    private void uploadImage(GpuImage image,ImageRegion area,ByteBuffer bytes,int pitch){if(transfers!=null)transfers.upload(image,area,bytes,pitch);else commands.upload(image,area,bytes,pitch);}
    private void clearColor(GpuImage image,int mip,int layer,float r,float g,float b,float a){if(transfers!=null)transfers.clearColor(image,mip,layer,r,g,b,a);else commands.clearColor(image,mip,layer,r,g,b,a);}
    private void target(int value){requireOpen();if(value!=GL_TEXTURE_2D)throw new UnsupportedOperationException("Only owned 2D textures are supported");}
    private void requireOpen(){context.device();if(closed)throw new IllegalStateException("Texture registry is closed");}
    @Override public void close(){
        if(closed)return;requireOpen();Throwable failure=null;
        for(var texture:textures)if(texture!=null)try{texture.close();}catch(RuntimeException|Error error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        if(incomplete!=null)try{incomplete.close();}catch(RuntimeException|Error error){if(failure==null)failure=error;else failure.addSuppressed(error);}finally{incomplete=null;}
        try{samplers.close();}catch(RuntimeException|Error error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        Arrays.fill(textures,null);Arrays.fill(bindings,0);closed=true;
        if(failure instanceof RuntimeException error)throw error;if(failure instanceof Error error)throw error;
    }
}
