package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.NativeGraphicsPipeline;
import static org.lwjgl.vulkan.VK10.*;

/**
 * OpenGL stencil state as GL reports it, lowered to the pipeline's stencil half. Front and back faces share one state,
 * as the single-sided glStencilFunc, glStencilOp and glStencilMask set them.
 */
public final class BlazeStencilState {
    private static final int KEEP = 0x1E00, ALWAYS = 0x207;
    private boolean enabled;
    private int function = ALWAYS, reference, valueMask = 0xFF, writeMask = 0xFF, fail = KEEP, depthFail = KEEP, pass = KEEP, clear;
    private NativeGraphicsPipeline.Stencil lowered;

    public void enable(boolean value) { if (enabled != value) { enabled = value; lowered = null; } }

    public void function(int gl, int ref, int mask) {
        if (gl < 0x200 || gl > 0x207) throw unsupported("stencil function", gl);
        function = gl; reference = ref; valueMask = mask; lowered = null;
    }

    public void mask(int mask) { writeMask = mask; lowered = null; }

    public void operation(int sfail, int dpfail, int dppass) {
        operation(sfail); operation(dpfail); operation(dppass);
        fail = sfail; depthFail = dpfail; pass = dppass; lowered = null;
    }

    public void clear(int value) { clear = value; }
    public int clearValue() { return clear; }
    public int writeMask() { return writeMask; }

    /** The pipeline's stencil state; a disabled test is the one shared disabled value, so pipelines key alike. */
    public NativeGraphicsPipeline.Stencil pipeline() {
        if (!enabled) return NativeGraphicsPipeline.Stencil.DISABLED;
        var value = lowered;
        if (value == null) lowered = value = new NativeGraphicsPipeline.Stencil(true, function - 0x200, reference & 0xFF,
                valueMask & 0xFF, writeMask & 0xFF, operation(fail), operation(depthFail), operation(pass));
        return value;
    }

    /** Answers the stencil queries, or null when the name is not stencil state. */
    public Integer integer(int name) {
        return switch (name) {
            case 0x0B90 -> enabled ? 1 : 0; case 0x0B91 -> clear; case 0x0B92 -> function; case 0x0B93 -> valueMask;
            case 0x0B94 -> fail; case 0x0B95 -> depthFail; case 0x0B96 -> pass; case 0x0B97 -> reference; case 0x0B98 -> writeMask;
            default -> null;
        };
    }

    private static int operation(int gl) {
        return switch (gl) {
            case 0 -> VK_STENCIL_OP_ZERO; case KEEP -> VK_STENCIL_OP_KEEP; case 0x1E01 -> VK_STENCIL_OP_REPLACE;
            case 0x1E02 -> VK_STENCIL_OP_INCREMENT_AND_CLAMP; case 0x1E03 -> VK_STENCIL_OP_DECREMENT_AND_CLAMP;
            case 0x150A -> VK_STENCIL_OP_INVERT; case 0x8507 -> VK_STENCIL_OP_INCREMENT_AND_WRAP; case 0x8508 -> VK_STENCIL_OP_DECREMENT_AND_WRAP;
            default -> throw unsupported("stencil operation", gl);
        };
    }

    private static UnsupportedOperationException unsupported(String name, int gl) { return new UnsupportedOperationException(name + ": 0x" + Integer.toHexString(gl)); }
}
