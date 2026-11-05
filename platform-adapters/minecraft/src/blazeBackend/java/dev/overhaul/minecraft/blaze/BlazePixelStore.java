package dev.overhaul.minecraft.blaze;

import static org.lwjgl.opengl.GL11.*;

/** GL pixel-store arithmetic is kept independent of texture lifetime and native transfers. */
final class BlazePixelStore {
    record Layout(int stride,int offset,int bytes){}
    private static final int UNPACK=GL_UNPACK_SWAP_BYTES,PACK=GL_PACK_SWAP_BYTES,EXTRA=32875,RUN=6,EXTRAS=4;
    /** The unpack run, the pack run and the skip-image and image-height values, in that order. */
    private final int[] values=new int[RUN+RUN+EXTRAS];
    BlazePixelStore(){
        values[slot(GL_PACK_ALIGNMENT)]=4;values[slot(GL_UNPACK_ALIGNMENT)]=4;
    }
    private static int slot(int name){
        if(name>=UNPACK&&name<UNPACK+RUN)return name-UNPACK;
        if(name>=PACK&&name<PACK+RUN)return RUN+name-PACK;
        if(name>=EXTRA&&name<EXTRA+EXTRAS)return RUN+RUN+name-EXTRA;
        return -1;
    }
    Integer integer(int name){int slot=slot(name);return slot<0?null:Integer.valueOf(values[slot]);}
    void set(int name,int value){
        int slot=slot(name);
        if(slot<0)throw new UnsupportedOperationException("Unknown owned pixel store: "+name);
        if(name==GL_PACK_ALIGNMENT||name==GL_UNPACK_ALIGNMENT){
            if(value!=1&&value!=2&&value!=4&&value!=8)throw new IllegalArgumentException("Invalid pixel alignment");
        }else if(value<0)throw new IllegalArgumentException("Negative pixel store");
        if((name==GL_PACK_SWAP_BYTES||name==GL_UNPACK_SWAP_BYTES||name==GL_PACK_LSB_FIRST||name==GL_UNPACK_LSB_FIRST)&&value!=0)
            throw new UnsupportedOperationException("Byte-swapped or bitmap pixel transfers are not supported");
        values[slot]=value;
    }
    Layout layout(boolean pack,int width,int height,int texelBytes){
        if(width<1||height<1)throw new IllegalArgumentException("Empty pixel transfer");
        int row=values[slot(pack?GL_PACK_ROW_LENGTH:GL_UNPACK_ROW_LENGTH)];if(row==0)row=width;
        int alignment=values[slot(pack?GL_PACK_ALIGNMENT:GL_UNPACK_ALIGNMENT)];
        long tight=Math.multiplyExact((long)row,texelBytes),stride=(tight+alignment-1)/alignment*alignment;
        long offset=Math.addExact(Math.multiplyExact((long)values[slot(pack?GL_PACK_SKIP_ROWS:GL_UNPACK_SKIP_ROWS)],stride),
                Math.multiplyExact((long)values[slot(pack?GL_PACK_SKIP_PIXELS:GL_UNPACK_SKIP_PIXELS)],texelBytes));
        long bytes=Math.addExact(offset,Math.addExact(Math.multiplyExact((long)height-1,stride),Math.multiplyExact((long)width,texelBytes)));
        return new Layout(Math.toIntExact(stride),Math.toIntExact(offset),Math.toIntExact(bytes));
    }
}
