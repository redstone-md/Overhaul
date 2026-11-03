package dev.overhaul.core.culling;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static dev.overhaul.core.culling.ConservativeProjection.ClipDepth.*;
import static org.junit.jupiter.api.Assertions.*;

class ConservativeProjectionTest {
    private static double[] identity(){return new double[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};}
    private static double[] perspective(){return new double[]{1,0,0,0,0,1,0,0,0,0,10.0/9,1,0,0,-10.0/9,0};}

    @Test void depthConventionsAndReversedDepthProduceTheSameConservativeNearestValue(){
        ConservativeProjection vk=new ConservativeProjection(identity(),20,20,VULKAN_ZERO_TO_ONE,false);
        ConservativeProjection reverseVk=new ConservativeProjection(identity(),20,20,VULKAN_ZERO_TO_ONE,true);
        ConservativeProjection gl=new ConservativeProjection(identity(),20,20,OPENGL_NEGATIVE_ONE_TO_ONE,false);
        ConservativeProjection reverseGl=new ConservativeProjection(identity(),20,20,OPENGL_NEGATIVE_ONE_TO_ONE,true);
        ConservativeProjection.ProjectedBounds[] bounds={vk.projectBounds(-.5,-.5,.2,.5,.5,.4),reverseVk.projectBounds(-.5,-.5,.6,.5,.5,.8),
                gl.projectBounds(-.5,-.5,-.6,.5,.5,-.2),reverseGl.projectBounds(-.5,-.5,.2,.5,.5,.6)};
        for(ConservativeProjection.ProjectedBounds bound:bounds){
            assertNotNull(bound);assertEquals(.2,bound.nearestDepth(),1e-6);assertTrue(bound.nearestDepth()<=.2);
            assertEquals(4,bound.minX());assertEquals(4,bound.minY());assertEquals(16,bound.maxX());assertEquals(16,bound.maxY());
        }
        double[] wall={-.9,-.9,.25,.9,-.9,.25,.9,.9,.25,-.9,.9,.25};double[] reversed=wall.clone();
        for(int index=2;index<12;index+=3)reversed[index]=.75;
        SoftwareDepthBuffer a=new SoftwareDepthBuffer(20,20),b=new SoftwareDepthBuffer(20,20);
        assertTrue(vk.rasterizeQuad(a,wall));assertTrue(reverseVk.rasterizeQuad(b,reversed));
        for(int y=0;y<20;y++)for(int x=0;x<20;x++)assertEquals(a.occluded(x,y,x+1,y+1,.5f),b.occluded(x,y,x+1,y+1,.5f));
        assertTrue(a.occluded(5,5,15,15,.5f));assertFalse(a.occluded(5,5,15,15,.25f));
    }

    @Test void perspectiveBoundsMatchAnalyticCornerExtremaAndUncertainBoundsRemainVisible(){
        ConservativeProjection projection=new ConservativeProjection(perspective(),100,80,VULKAN_ZERO_TO_ONE,false);
        ConservativeProjection.ProjectedBounds bounds=projection.projectBounds(-.2,-.2,2,.2,.2,4);
        assertNotNull(bounds);assertEquals(44,bounds.minX());assertEquals(56,bounds.maxX());assertEquals(35,bounds.minY());assertEquals(45,bounds.maxY());
        assertEquals(5.0/9,bounds.nearestDepth(),1e-6);assertTrue(bounds.nearestDepth()<=5.0/9);
        assertNull(projection.projectBounds(-.2,-.2,.5,.2,.2,2)); // Near crossing.
        assertNull(projection.projectBounds(-.2,-.2,-3,.2,.2,-2)); // Behind camera.
        assertNull(projection.projectBounds(-4,-.2,2,.2,.2,3)); // Viewport crossing.
        assertNull(projection.projectBounds(-.2,-.2,9,.2,.2,11)); // Far crossing.
        assertNull(projection.projectBounds(Double.NaN,0,1,1,1,2));assertNull(projection.projectBounds(1,0,1,0,1,2));
    }

    @Test void homogeneousNearAndViewportClippingDoNotInventCoverage(){
        ConservativeProjection projection=new ConservativeProjection(perspective(),32,32,VULKAN_ZERO_TO_ONE,false);
        SoftwareDepthBuffer viewport=new SoftwareDepthBuffer(32,32);
        assertTrue(projection.rasterizeQuad(viewport,new double[]{-4,-4,2,4,-4,2,4,4,2,-4,4,2}));
        assertTrue(viewport.occluded(2,2,30,30,.8f));assertFalse(viewport.occluded(2,2,30,30,.55f));
        SoftwareDepthBuffer near=new SoftwareDepthBuffer(32,32);
        assertTrue(projection.rasterizeQuad(near,new double[]{-.8,-.8,.5,.8,-.8,2,.8,.8,2,-.8,.8,.5}));
        assertTrue(near.occluded(16,15,18,17,.9f));assertFalse(near.occluded(5,15,6,16,.9f));
        SoftwareDepthBuffer behind=new SoftwareDepthBuffer(32,32);
        assertFalse(projection.rasterizeQuad(behind,new double[]{-.5,-.5,-2,.5,-.5,-2,.5,.5,-2,-.5,.5,-2}));
        assertFalse(behind.occluded(15,15,16,16,.9f));
    }

    @Test void windingAndLargeWorldCameraRelativeSubtractionPreserveProjection(){
        ConservativeProjection projection=new ConservativeProjection(identity(),64,64,VULKAN_ZERO_TO_ONE,false);
        double camera=30_000_000.25;
        ConservativeProjection.ProjectedBounds large=projection.projectBounds(30_000_000.5-camera,-.25,.2,30_000_000.75-camera,.25,.4);
        ConservativeProjection.ProjectedBounds small=projection.projectBounds(.25,-.25,.2,.5,.25,.4);
        assertNotNull(large);assertEquals(small.minX(),large.minX());assertEquals(small.maxX(),large.maxX());
        ConservativeProjection.ProjectedBounds imprecise=projection.projectBounds((float)30_000_000.5-camera,-.25,.2,(float)30_000_000.75-camera,.25,.4);
        assertNotEquals(large.minX(),imprecise.minX(),"Casting world coordinates before camera subtraction loses sub-block geometry");
        double[] clockwise={-.7,-.8,.3,-.7,.8,.3,.7,.8,.3,.7,-.8,.3};double[] counterclockwise=new double[12];
        for(int index=0;index<4;index++)System.arraycopy(clockwise,index*3,counterclockwise,(3-index)*3,3);
        SoftwareDepthBuffer a=new SoftwareDepthBuffer(64,64),b=new SoftwareDepthBuffer(64,64);
        assertTrue(projection.rasterizeQuad(a,clockwise));assertTrue(projection.rasterizeQuad(b,counterclockwise));
        for(int y=0;y<64;y++)for(int x=0;x<64;x++)assertEquals(a.occluded(x,y,x+1,y+1,.8f),b.occluded(x,y,x+1,y+1,.8f));
    }

    @Test void rasterCoverageAgreesWithIndependentPerspectiveRectangleOracle(){
        int width=29,height=17;Random random=new Random(7281);
        ConservativeProjection projection=new ConservativeProjection(perspective(),width,height,VULKAN_ZERO_TO_ONE,false);
        for(int trial=0;trial<80;trial++){
            double z=1.5+random.nextDouble()*6,ndcLeft=-.9+random.nextDouble()*.6,ndcRight=.3+random.nextDouble()*.6;
            double ndcTop=-.9+random.nextDouble()*.6,ndcBottom=.3+random.nextDouble()*.6;
            double left=(ndcLeft+1)*width/2,right=(ndcRight+1)*width/2,top=(ndcTop+1)*height/2,bottom=(ndcBottom+1)*height/2;
            double depth=10.0/9-10.0/(9*z);SoftwareDepthBuffer buffer=new SoftwareDepthBuffer(width,height);
            assertTrue(projection.rasterizeQuad(buffer,new double[]{ndcLeft*z,ndcTop*z,z,ndcRight*z,ndcTop*z,z,ndcRight*z,ndcBottom*z,z,ndcLeft*z,ndcBottom*z,z}));
            for(int y=0;y<height;y++)for(int x=0;x<width;x++){
                boolean hidden=buffer.occluded(x,y,x+1,y+1,(float)Math.min(1,depth+.1));
                if(hidden)assertTrue(x>left&&x+1<right&&y>top&&y+1<bottom,"A covered software cell must be wholly inside the analytic quad");
                if(x>left+.25&&x+1<right-.25&&y>top+.25&&y+1<bottom-.25)assertTrue(hidden,"Cells well inside the analytic polygon should contribute useful occlusion");
                assertFalse(buffer.occluded(x,y,x+1,y+1,(float)depth),"Equal-depth surfaces must stay visible");
            }
        }
    }

    @Test void nonplanarConcaveDegenerateAndInvalidInputsFailOpen(){
        ConservativeProjection projection=new ConservativeProjection(identity(),16,16,VULKAN_ZERO_TO_ONE,false);SoftwareDepthBuffer buffer=new SoftwareDepthBuffer(16,16);
        assertFalse(projection.rasterizeQuad(buffer,new double[]{-.8,-.8,.2,.8,-.8,.2,.8,.8,.3,-.8,.8,.2}));
        assertFalse(projection.rasterizeQuad(buffer,new double[]{-.8,-.8,.2,.8,.8,.2,.8,-.8,.2,-.8,.8,.2}));
        assertFalse(projection.rasterizeQuad(buffer,new double[]{0,0,.2,0,0,.2,0,0,.2,0,0,.2}));
        assertFalse(projection.rasterizeQuad(buffer,new double[]{Double.POSITIVE_INFINITY,0,.2,1,0,.2,1,1,.2,0,1,.2}));
        assertFalse(buffer.occluded(1,1,15,15,.9f));
        assertThrows(IllegalArgumentException.class,()->new ConservativeProjection(new double[16],16,16,VULKAN_ZERO_TO_ONE,false));
        double[] invalid=identity();invalid[5]=Double.NaN;assertThrows(IllegalArgumentException.class,()->new ConservativeProjection(invalid,16,16,VULKAN_ZERO_TO_ONE,false));
        double[] copied=identity();ConservativeProjection independent=new ConservativeProjection(copied,16,16,VULKAN_ZERO_TO_ONE,false);copied[0]=0;
        assertNotNull(independent.projectBounds(-.2,-.2,.2,.2,.2,.4));
        assertThrows(IllegalArgumentException.class,()->projection.rasterizeQuad(new SoftwareDepthBuffer(8,8),new double[12]));
    }
    @Test void precisionGuardCanPushOccluderDepthFartherAway(){
        ConservativeProjection projection=new ConservativeProjection(identity(),16,16,VULKAN_ZERO_TO_ONE,false);
        SoftwareDepthBuffer buffer=new SoftwareDepthBuffer(16,16);
        double[] face={-.9,-.9,.2,.9,-.9,.2,.9,.9,.2,-.9,.9,.2};
        assertTrue(projection.rasterizeQuad(buffer,face,0,.6));
        assertFalse(buffer.occluded(4,4,12,12,.5f));assertTrue(buffer.occluded(4,4,12,12,.8f));
        assertThrows(IllegalArgumentException.class,()->projection.rasterizeQuad(buffer,face,0,Double.NaN));
    }
}
