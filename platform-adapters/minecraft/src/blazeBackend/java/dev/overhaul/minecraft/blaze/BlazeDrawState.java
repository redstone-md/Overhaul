package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.NativeGraphicsPipeline;
import static org.lwjgl.vulkan.VK10.*;

/** Render-thread state owned by one selected window; draw snapshots are immutable. */
public final class BlazeDrawState {
    private boolean depth, depthWrite = true, blend, cull, scissor, logic;
    private int compare = VK_COMPARE_OP_LESS, sourceRgb = VK_BLEND_FACTOR_ONE, destinationRgb = VK_BLEND_FACTOR_ZERO;
    private int sourceAlpha = VK_BLEND_FACTOR_ONE, destinationAlpha = VK_BLEND_FACTOR_ZERO, equation = VK_BLEND_OP_ADD;
    private int colorMask = 15, logicOp = VK_LOGIC_OP_COPY;
    private int x, y, width, height, sx, sy, sw, sh;
    private int drawFramebuffer,readFramebuffer;
    private boolean polygonOffset;
    private float offsetFactor,offsetUnits,lineWidth=1,clearDepth=1;
    private float clearR,clearG,clearB,clearA;
    private int polygonMode=VK_POLYGON_MODE_FILL;

    public record Snapshot(NativeGraphicsPipeline.Depth depth, NativeGraphicsPipeline.Color color,
            NativeGraphicsPipeline.ColorLogic logic, int cull, int polygonMode,float lineWidth,int x, int y, int width, int height,
            boolean scissor, int sx, int sy, int sw, int sh) {}
    public record Clear(float r,float g,float b,float a,float depth){}

    public Snapshot snapshot() {
        return new Snapshot(new NativeGraphicsPipeline.Depth(depth, depthWrite, compare, polygonOffset?offsetUnits:0, polygonOffset?offsetFactor:0),
                new NativeGraphicsPipeline.Color(blend, sourceRgb, destinationRgb, equation, sourceAlpha, destinationAlpha, equation, colorMask),
                new NativeGraphicsPipeline.ColorLogic(logic, logicOp), cull ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE,
                polygonMode,lineWidth,x, y, width, height, scissor, sx, sy, sw, sh);
    }

    public void depth(boolean value) { depth = value; }
    public void depthWrite(boolean value) { depthWrite = value; }
    public void depthFunction(int gl) { if (gl < 0x200 || gl > 0x207) throw unsupported("depth function", gl); compare = gl - 0x200; }
    public void blend(boolean value) { blend = value; }
    public void cull(boolean value) { cull = value; }
    public void scissor(boolean value) { scissor = value; }
    public void logic(boolean value) { logic = value; }
    public void polygonOffset(boolean value){polygonOffset=value;}
    public void polygonOffset(float factor,float units){if(!Float.isFinite(factor)||!Float.isFinite(units))throw new IllegalArgumentException("Nonfinite depth bias");offsetFactor=factor;offsetUnits=units;}
    public void polygonMode(int face,int mode){if(face!=0x408)throw unsupported("separate polygon faces",face);polygonMode=switch(mode){case 0x1B00->VK_POLYGON_MODE_POINT;case 0x1B01->VK_POLYGON_MODE_LINE;case 0x1B02->VK_POLYGON_MODE_FILL;default->throw unsupported("polygon mode",mode);};}
    public void lineWidth(float width){if(!Float.isFinite(width)||width<=0)throw new IllegalArgumentException("Invalid line width");lineWidth=width;}
    public void clearColor(float r,float g,float b,float a){clearR=r;clearG=g;clearB=b;clearA=a;}
    public void clearDepth(double depth){if(!Double.isFinite(depth))throw new IllegalArgumentException("Nonfinite clear depth");clearDepth=(float)Math.max(0,Math.min(1,depth));}
    public Clear clear(){return new Clear(clearR,clearG,clearB,clearA,clearDepth);}
    public void logicOperation(int gl) { if (gl < 0x1500 || gl > 0x150F) throw unsupported("logic operation", gl); logicOp = gl - 0x1500; }
    public void viewport(int x, int y, int width, int height) { dimensions(width, height); this.x=x; this.y=y; this.width=width; this.height=height; }
    public void scissorBox(int x, int y, int width, int height) { dimensions(width, height); sx=x; sy=y; sw=width; sh=height; }
    public void colorMask(boolean r, boolean g, boolean b, boolean a) { colorMask=(r?1:0)|(g?2:0)|(b?4:0)|(a?8:0); }
    public void blendFunctions(int sr, int dr, int sa, int da) { sourceRgb=factor(sr); destinationRgb=factor(dr); sourceAlpha=factor(sa); destinationAlpha=factor(da); }
    public void blendEquation(int gl) {
        equation=switch(gl){case 0x8006->VK_BLEND_OP_ADD;case 0x8007->VK_BLEND_OP_MIN;case 0x8008->VK_BLEND_OP_MAX;
            case 0x800A->VK_BLEND_OP_SUBTRACT;case 0x800B->VK_BLEND_OP_REVERSE_SUBTRACT;default->throw unsupported("blend equation",gl);};
    }

    public void framebufferBindings(int draw,int read){drawFramebuffer=draw;readFramebuffer=read;}
    public Integer integer(int name){
        return switch(name){case 0x0B71->depth?1:0;case 0x0B72->depthWrite?1:0;case 0x0B74->compare+0x200;
            case 0x0BE2->blend?1:0;case 0x0B44->cull?1:0;case 0x0C11->scissor?1:0;
            case 0x0BF2->logic?1:0;case 0x0BF0->logicOp+0x1500;case 0x8CA6->drawFramebuffer;case 0x8CAA->readFramebuffer;
            default->null;};
    }

    private static int factor(int gl) {
        return switch(gl){case 0->VK_BLEND_FACTOR_ZERO;case 1->VK_BLEND_FACTOR_ONE;
            case 0x300->VK_BLEND_FACTOR_SRC_COLOR;case 0x301->VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case 0x302->VK_BLEND_FACTOR_SRC_ALPHA;case 0x303->VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case 0x304->VK_BLEND_FACTOR_DST_ALPHA;case 0x305->VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case 0x306->VK_BLEND_FACTOR_DST_COLOR;case 0x307->VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case 0x308->VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
            default->throw unsupported("blend factor (blend constants are not implemented)",gl);};
    }
    private static void dimensions(int width,int height){if(width<0||height<0)throw new IllegalArgumentException("Negative raster dimensions");}
    private static UnsupportedOperationException unsupported(String name,int gl){return new UnsupportedOperationException(name+": 0x"+Integer.toHexString(gl));}
}
