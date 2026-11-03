package dev.overhaul.core.culling;

import org.junit.jupiter.api.Test;
import static dev.overhaul.core.culling.ConservativeProjection.ClipDepth.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectionPrecisionTest {
    private static float[] identity(){return new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};}
    private static float[] reversed(float scale){return new float[]{scale,0,0,0,0,scale,0,0,0,0,0,1,0,0,.05f,0};}

    @Test void rejectsActualFloat32NearClippingCoverageCounterexampleForSixteenBlockFace(){
        double angle=1.2546842105263158;float c=(float)Math.cos(angle),s=(float)Math.sin(angle),x=-.04696409031748772f;
        float[] view={c,0,-s,0,0,1,0,0,s,0,c,0,0,0,0,1};
        double[] quad={x,-8,-8,x,8,-8,x,8,8,x,-8,8};
        double cpuLeft=nearEdge(x,c,s,8,false),gpuLeft=nearEdge(x,c,s,8,true);
        assertTrue(cpuLeft<125.99);assertTrue(gpuLeft>126);
        assertTrue(126-cpuLeft>.01,"The old inset falsely certified the entire cell beginning at126");
        ProjectionPrecision guard=new ProjectionPrecision(reversed(16),view,160,90,VULKAN_ZERO_TO_ONE,true);
        assertFalse(guard.safeQuad(quad,0),"Original near-plane crossings must never become certified occluders");
    }
    @Test void acceptsWellConditionedFacesAndBoundsWithOutwardMargins(){
        ProjectionPrecision guard=new ProjectionPrecision(reversed(1),identity(),160,90,VULKAN_ZERO_TO_ONE,true);
        double[] wall={-1,-1,5,1,-1,5,1,1,5,-1,1,5};
        assertTrue(guard.safeQuad(wall,0));assertTrue(guard.screenError()<.002);assertTrue(guard.depthError()<1e-6);
        assertTrue(guard.occluderDepth()>.99);assertTrue(guard.occluderDepth()<.990001);
        assertTrue(guard.safeBounds(-1,-1,6,1,1,8));assertTrue(guard.screenError()<.25);assertTrue(guard.depthError()<1e-5);
        assertFalse(guard.safeBounds(-1,-1,-6,1,1,-5));assertFalse(guard.safeBounds(Double.NaN,0,1,1,1,2));
        assertFalse(guard.safeQuad(new double[]{0,0,2_000_000,1,0,2_000_000,1,1,2_000_000,0,1,2_000_000},0));
    }
    @Test void separateMatrixCancellationIsBudgetedEvenWhenCombinedTransformLooksHarmless(){
        float[] view=identity(),projection=reversed(1);view[12]=10000;projection[12]=-10000;
        double[] quad={-.5,-.5,5,.5,-.5,5,.5,.5,5,-.5,.5,5};
        ProjectionPrecision baseline=new ProjectionPrecision(reversed(1),identity(),160,90,VULKAN_ZERO_TO_ONE,true);
        assertTrue(baseline.safeQuad(quad,0));assertTrue(baseline.safeBounds(-.5,-.5,5,.5,.5,6));
        ProjectionPrecision guard=new ProjectionPrecision(projection,view,160,90,VULKAN_ZERO_TO_ONE,true);
        assertFalse(guard.safeQuad(quad,0));assertFalse(guard.safeBounds(-.5,-.5,5,.5,.5,6));
    }
    @Test void originalFarDepthSurvivesViewportClippingAndSupportsBothDepthConventions(){
        ProjectionPrecision guard=new ProjectionPrecision(reversed(1),identity(),160,90,VULKAN_ZERO_TO_ONE,true);
        double[] tilted={-2,-1,4,2,-1,8,2,1,8,-2,1,4};assertTrue(guard.safeQuad(tilted,0));
        assertTrue(guard.occluderDepth()>=1-.05/8,"Depth floor includes original geometry, not a nearer clipped polygon");
        float[] gl={1,0,0,0,0,1,0,0,0,0,-1,-1,0,0,-.1f,0};
        ProjectionPrecision normalGl=new ProjectionPrecision(gl,identity(),160,90,OPENGL_NEGATIVE_ONE_TO_ONE,false);
        assertTrue(normalGl.safeBounds(-1,-1,-8,1,1,-4));
    }
    @Test void expandedBudgetContainsBothFloatAssociationsDespiteIntermediateCancellation(){
        float[] view={193.123f,-29.317f,0,0,-67.357f,174.875f,0,0,12.133f,66.127f,1,0,-137.8990020751953f,-211.68499755859375f,0,1};
        float[] projection={1.193f,.133f,0,0,-.917f,1.719f,0,0,0,0,.1f,0,0,0,.4f,1};float[] input={1,1,1,1};
        float[] left=vector(matrix(projection,view),input),right=vector(projection,vector(view,input));
        assertNotEquals(left[1],right[1],"Rounded matrix product and rounded intermediate vector produce different results");
        ProjectionPrecision guard=new ProjectionPrecision(projection,view,160,90,VULKAN_ZERO_TO_ONE,false);
        assertTrue(guard.safeBounds(1,1,1,1,1,1));double[] reference=reference(projection,view,input);
        for(float[] gpu:new float[][]{left,right}){
            assertTrue(Math.abs(gpu[0]/(double)gpu[3]-reference[0]/reference[3])*80<=guard.screenError());
            assertTrue(Math.abs(gpu[1]/(double)gpu[3]-reference[1]/reference[3])*45<=guard.screenError());
            assertTrue(Math.abs(gpu[2]/(double)gpu[3]-reference[2]/reference[3])<=guard.depthError());
        }
    }
    private static float[] matrix(float[] a,float[] b){
        float[] output=new float[16];for(int col=0;col<4;col++)for(int row=0;row<4;row++)for(int k=0;k<4;k++)output[col*4+row]+=a[k*4+row]*b[col*4+k];return output;
    }
    private static float[] vector(float[] matrix,float[] point){
        float[] output=new float[4];for(int row=0;row<4;row++)for(int col=0;col<4;col++)output[row]+=matrix[col*4+row]*point[col];return output;
    }
    private static double[] reference(float[] a,float[] b,float[] point){
        double[] output=new double[4];for(int row=0;row<4;row++)for(int col=0;col<4;col++){
            double coefficient=0;for(int k=0;k<4;k++)coefficient+=(double)a[k*4+row]*b[col*4+k];output[row]+=coefficient*point[col];
        }return output;
    }
    /** Independent near-plane intersection of float-transformed endpoints, followed by perspective division. */
    private static double nearEdge(float x,float c,float s,float extent,boolean floatGpu){
        double ax,bx,aw,bw;
        if(floatGpu){ax=16f*(c*x+s*(-extent));bx=16f*(c*x+s*extent);aw=-s*x+c*(-extent);bw=-s*x+c*extent;}
        else{ax=16*((double)c*x+(double)s*(-extent));bx=16*((double)c*x+(double)s*extent);aw=-(double)s*x+(double)c*(-extent);bw=-(double)s*x+(double)c*extent;}
        double fraction=((double).05f-aw)/(bw-aw),clipX=ax+fraction*(bx-ax);return 80*(1+clipX/.05f);
    }
}
