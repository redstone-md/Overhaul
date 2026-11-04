package dev.overhaul.shader.pack;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Header-first PNG decoding; no disk cache, ancillary metadata or image-sized allocation before bounds. */
final class PackPngDecoder {
    private PackPngDecoder(){}
    static PackTextures.Image decode(byte[] encoded,PackTextureMetadata metadata,long remainingBytes)throws IOException{
        try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded))){
            var readers=ImageIO.getImageReadersByFormatName("PNG");
            if(!readers.hasNext())throw new IOException("No PNG decoder is installed");
            var reader=readers.next();
            try{
                reader.setInput(input,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);
                long bytes=(long)width*height*4;
                if(width<1||height<1||width>4096||height>4096||bytes>remainingBytes)throw new IOException("Pack PNG exceeds dimension or decoded-byte bounds");
                var image=reader.read(0);byte[] rgba=new byte[(int)bytes];
                try{
                    // NativeImage/Iris upload file row zero directly; no vertical image flip.
                    for(int y=0;y<height;y++)for(int x=0;x<width;x++){
                        int argb=image.getRGB(x,y),offset=(y*width+x)*4;
                        rgba[offset]=(byte)(argb>>>16);rgba[offset+1]=(byte)(argb>>>8);rgba[offset+2]=(byte)argb;rgba[offset+3]=(byte)(argb>>>24);
                    }
                }finally{image.flush();}
                return new PackTextures.Image(width,height,rgba,metadata.blur(),metadata.clamp());
            }finally{reader.dispose();}
        }
    }
}
