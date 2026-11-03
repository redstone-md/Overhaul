package dev.overhaul.minecraft.blaze;

import java.util.HashMap;
import java.util.Map;
import static org.lwjgl.opengl.GL11.*;

/** GL pixel-store arithmetic is kept independent of texture lifetime and native transfers. */
final class BlazePixelStore {
    record Layout(int stride,int offset,int bytes){}
    private final Map<Integer,Integer> values=new HashMap<>();
    BlazePixelStore(){
        for(int name:new int[]{GL_PACK_ROW_LENGTH,GL_PACK_SKIP_ROWS,GL_PACK_SKIP_PIXELS,GL_UNPACK_ROW_LENGTH,
                GL_UNPACK_SKIP_ROWS,GL_UNPACK_SKIP_PIXELS,GL_PACK_SWAP_BYTES,GL_UNPACK_SWAP_BYTES,GL_PACK_LSB_FIRST,
                GL_UNPACK_LSB_FIRST,32875,32876,32877,32878})values.put(name,0);
        values.put(GL_PACK_ALIGNMENT,4);values.put(GL_UNPACK_ALIGNMENT,4);
    }
    Integer integer(int name){return values.get(name);}
    void set(int name,int value){
        if(!values.containsKey(name))throw new UnsupportedOperationException("Unknown owned pixel store: "+name);
        if(name==GL_PACK_ALIGNMENT||name==GL_UNPACK_ALIGNMENT){
            if(value!=1&&value!=2&&value!=4&&value!=8)throw new IllegalArgumentException("Invalid pixel alignment");
        }else if(value<0)throw new IllegalArgumentException("Negative pixel store");
        if((name==GL_PACK_SWAP_BYTES||name==GL_UNPACK_SWAP_BYTES||name==GL_PACK_LSB_FIRST||name==GL_UNPACK_LSB_FIRST)&&value!=0)
            throw new UnsupportedOperationException("Byte-swapped or bitmap pixel transfers are not supported");
        values.put(name,value);
    }
    Layout layout(boolean pack,int width,int height,int texelBytes){
        if(width<1||height<1)throw new IllegalArgumentException("Empty pixel transfer");
        int row=values.get(pack?GL_PACK_ROW_LENGTH:GL_UNPACK_ROW_LENGTH);if(row==0)row=width;
        int alignment=values.get(pack?GL_PACK_ALIGNMENT:GL_UNPACK_ALIGNMENT);
        long tight=Math.multiplyExact((long)row,texelBytes),stride=(tight+alignment-1)/alignment*alignment;
        long offset=Math.addExact(Math.multiplyExact((long)values.get(pack?GL_PACK_SKIP_ROWS:GL_UNPACK_SKIP_ROWS),stride),
                Math.multiplyExact((long)values.get(pack?GL_PACK_SKIP_PIXELS:GL_UNPACK_SKIP_PIXELS),texelBytes));
        long bytes=Math.addExact(offset,Math.addExact(Math.multiplyExact((long)height-1,stride),Math.multiplyExact((long)width,texelBytes)));
        return new Layout(Math.toIntExact(stride),Math.toIntExact(offset),Math.toIntExact(bytes));
    }
}
