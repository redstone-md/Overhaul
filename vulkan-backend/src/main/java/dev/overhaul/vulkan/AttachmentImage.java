package dev.overhaul.vulkan;

/** Compatibility wrapper; native image allocation is authoritative in GpuAllocator. */
final class AttachmentImage implements AutoCloseable {
    private final GpuAllocator allocator;
    private final boolean ownsAllocator;
    private GpuImage resource;
    long image;
    long view;

    AttachmentImage(VulkanContext context,int width,int height,int format,int usage,int aspect){
        this(new GpuAllocator(context),true,width,height,format,usage,aspect);
    }
    AttachmentImage(GpuAllocator allocator,int width,int height,int format,int usage,int aspect){
        this(allocator,false,width,height,format,usage,aspect);
    }
    private AttachmentImage(GpuAllocator allocator,boolean ownsAllocator,int width,int height,int format,int usage,int aspect){
        this.allocator=allocator;this.ownsAllocator=ownsAllocator;
        try{
            ImageFormat typed=ImageFormat.fromVk(format);
            if(typed.aspectMask()!=aspect)throw new IllegalArgumentException("attachment aspect differs from format");
            resource=allocator.createImage(typed,width,height,1,1,usage);
            image=resource.handle();view=resource.view(0,1,0,1).handle();
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    @Override public void close(){
        if(resource!=null){resource.close();resource=null;}
        image=view=0;if(ownsAllocator)allocator.close();
    }
}
