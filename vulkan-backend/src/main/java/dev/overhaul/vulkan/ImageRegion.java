package dev.overhaul.vulkan;

/** A 2D rectangle in one array layer of one mip level; in a 3D image, the rectangle of one depth slice. */
public record ImageRegion(int mipLevel,int arrayLayer,int x,int y,int width,int height,int slice) {
    public ImageRegion(int mipLevel,int arrayLayer,int x,int y,int width,int height){this(mipLevel,arrayLayer,x,y,width,height,0);}
    public static ImageRegion whole(GpuImage image,int mip,int layer){return new ImageRegion(mip,layer,0,0,image.width(mip),image.height(mip));}
    void validate(GpuImage image){
        image.requireOpen();image.checkSubresource(mipLevel,arrayLayer);
        if(x<0||y<0||width<1||height<1||x>image.width(mipLevel)-width||y>image.height(mipLevel)-height)
            throw new IllegalArgumentException("image region exceeds mip dimensions");
        if(slice<0||slice>=image.depth())throw new IllegalArgumentException("image region slice exceeds image depth");
    }
    int rowPitch(GpuImage image,int pitch){
        int tight=Math.multiplyExact(width,image.format().bytesPerPixel());
        if(pitch==0)return tight;
        if(pitch<tight||pitch%image.format().bytesPerPixel()!=0)throw new IllegalArgumentException("invalid image row pitch");
        return pitch;
    }
    int byteSize(GpuImage image,int pitch){return Math.addExact(Math.multiplyExact(height-1,rowPitch(image,pitch)),Math.multiplyExact(width,image.format().bytesPerPixel()));}
}
