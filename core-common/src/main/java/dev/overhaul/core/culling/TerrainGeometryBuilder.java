package dev.overhaul.core.culling;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Fixed face-mask budget; unknown, partial, non-grid and malformed faces never become occluders. */
public final class TerrainGeometryBuilder {
    private static final int MAX_RECTANGLES=4096;
    private final short[] rows=new short[6*17*16];
    private final double[] bounds={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
    private boolean uncertain,built;

    public void vertex(float x,float y,float z) {
        if(built)throw new IllegalStateException("Geometry builder finished");
        if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z)){uncertain=true;return;}
        bounds[0]=Math.min(bounds[0],x);bounds[1]=Math.min(bounds[1],y);bounds[2]=Math.min(bounds[2],z);
        bounds[3]=Math.max(bounds[3],x);bounds[4]=Math.max(bounds[4],y);bounds[5]=Math.max(bounds[5],z);
    }
    /** XYZ order must match the four actually emitted vertices of a SOLID quad. */
    public boolean solidQuad(float[] xyz) {
        if(built)throw new IllegalStateException("Geometry builder finished");
        if(xyz==null||xyz.length!=12)throw new IllegalArgumentException("Four XYZ corners required");
        for(float value:xyz)if(!Float.isFinite(value)||value!=Math.rint(value)||value<0||value>16)return false;
        for(int axis=0;axis<3;axis++) {
            int plane=(int)xyz[axis],u=(axis+1)%3,v=(axis+2)%3;
            if(xyz[3+axis]!=plane||xyz[6+axis]!=plane||xyz[9+axis]!=plane)continue;
            int minU=16,minV=16,maxU=0,maxV=0;
            for(int i=0;i<4;i++){minU=Math.min(minU,(int)xyz[i*3+u]);minV=Math.min(minV,(int)xyz[i*3+v]);maxU=Math.max(maxU,(int)xyz[i*3+u]);maxV=Math.max(maxV,(int)xyz[i*3+v]);}
            if(maxU-minU!=1||maxV-minV!=1)continue;
            int corners=0,sign=0;
            for(int i=0;i<4;i++) {
                int a=i*3,b=(i+1)%4*3,c=(i+2)%4*3;
                int corner=((int)xyz[a+u]-minU)+2*((int)xyz[a+v]-minV);
                if((corners&(1<<corner))!=0)return false;
                corners|=1<<corner;
                int cross=(int)((xyz[b+u]-xyz[a+u])*(xyz[c+v]-xyz[b+v])-(xyz[b+v]-xyz[a+v])*(xyz[c+u]-xyz[b+u]));
                if(Math.abs(cross)!=1||sign!=0&&sign!=cross)return false;
                sign=cross;
            }
            int direction=axis*2+(sign>0?0:1);
            rows[(direction*17+plane)*16+minV]|=1<<minU;
            return true;
        }
        return false;
    }
    public TerrainGeometry build() {
        if(built)throw new IllegalStateException("Geometry builder finished");built=true;
        ByteArrayOutputStream faces=new ByteArrayOutputStream();
        for(int direction=0;direction<6;direction++)for(int plane=0;plane<=16;plane++) {
            int base=(direction*17+plane)*16;
            for(int y=0;y<16;y++)for(int x=0;x<16;x++)if((rows[base+y]&(1<<x))!=0) {
                int width=1;while(x+width<16&&(rows[base+y]&(1<<(x+width)))!=0)width++;
                int mask=((1<<width)-1)<<x,height=1;
                while(y+height<16&&(rows[base+y+height]&mask)==mask)height++;
                for(int row=0;row<height;row++)rows[base+y+row]&=~mask;
                if(faces.size()<MAX_RECTANGLES*6){faces.write(direction);faces.write(plane);faces.write(x);faces.write(y);faces.write(x+width);faces.write(y+height);}
            }
        }
        if(uncertain)Arrays.fill(bounds,Double.NaN);
        return new TerrainGeometry(bounds,faces.toByteArray());
    }
}
