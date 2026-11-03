package dev.overhaul.core.culling;

/** Finite perspective center of a final clip transform, in its input coordinate system. */
public final class PerspectiveEye {
    private final double x,y,z;
    private PerspectiveEye(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
    /** Inverse(matrix) * (0,0,1,0), then homogeneous divide. Uncertain/orthographic transforms return null. */
    public static PerspectiveEye fromMatrix(double[] matrix) {
        if(matrix==null||matrix.length!=16)return null;
        double norm=0;
        for(double value:matrix){if(!Double.isFinite(value))return null;norm=Math.max(norm,Math.abs(value));}
        if(norm==0)return null;
        double[] augmented=new double[20];
        for(int row=0;row<4;row++) {
            for(int column=0;column<4;column++)augmented[row*5+column]=matrix[column*4+row]/norm;
            augmented[row*5+4]=row==2?1:0;
        }
        for(int column=0;column<4;column++) {
            int pivot=column;
            for(int row=column+1;row<4;row++)if(Math.abs(augmented[row*5+column])>Math.abs(augmented[pivot*5+column]))pivot=row;
            double divisor=augmented[pivot*5+column];
            if(!Double.isFinite(divisor)||Math.abs(divisor)<1e-10)return null;
            if(pivot!=column)for(int field=0;field<5;field++){double swap=augmented[column*5+field];augmented[column*5+field]=augmented[pivot*5+field];augmented[pivot*5+field]=swap;}
            for(int field=column;field<5;field++)augmented[column*5+field]/=divisor;
            for(int row=0;row<4;row++)if(row!=column) {
                double factor=augmented[row*5+column];
                for(int field=column;field<5;field++)augmented[row*5+field]-=factor*augmented[column*5+field];
            }
        }
        double hx=augmented[4],hy=augmented[9],hz=augmented[14],hw=augmented[19];
        double magnitude=Math.max(1,Math.max(Math.abs(hx),Math.max(Math.abs(hy),Math.abs(hz))));
        if(!Double.isFinite(hw)||Math.abs(hw)<1e-10*magnitude)return null;
        double x=hx/hw,y=hy/hw,z=hz/hw;
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))return null;
        // Confirm the solved point maps to the clip-space perspective center.
        for(int row:new int[]{0,1,3}) {
            double residual=(matrix[row]*x+matrix[4+row]*y+matrix[8+row]*z+matrix[12+row])/norm;
            if(!Double.isFinite(residual)||Math.abs(residual)>1e-9*(1+Math.abs(x)+Math.abs(y)+Math.abs(z)))return null;
        }
        return new PerspectiveEye(x,y,z);
    }
    public double coordinate(int axis){if(axis<0||axis>2)throw new IllegalArgumentException("Axis must be 0..2");return axis==0?x:axis==1?y:z;}
    /** A plane too close to the effective eye never contributes an occluder. */
    public boolean faces(int axis,int sign,double plane) {
        if((sign!=1&&sign!=-1)||!Double.isFinite(plane))return false;
        double eye=coordinate(axis),margin=1e-7*(1+Math.abs(plane)+Math.abs(eye));
        return sign*(eye-plane)>margin;
    }
}
