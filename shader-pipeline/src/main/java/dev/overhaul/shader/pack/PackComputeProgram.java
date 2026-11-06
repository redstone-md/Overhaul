package dev.overhaul.shader.pack;

import dev.overhaul.shader.compat.PackProgramTranslator;
import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.compile.SpirvBinary;
import dev.overhaul.shader.compile.SpirvInterface;
import java.util.regex.Pattern;

/**
 * One {@code <program>.csh} or {@code <program>_<a-z>.csh}: dispatched before the fullscreen pass of the same stage
 * and ordinal, in suffix order. {@code workGroups} is null when the dispatch covers the screen scaled by
 * {@code workGroupsRender}, in which case each group covers {@code localSize} pixels.
 */
public record PackComputeProgram(String name, CompiledShaderPack.Stage stage, int ordinal, String suffix, SpirvBinary code,
                                 ProgramBindings bindings, int[] localSize, int[] workGroups, float[] workGroupsRender) {
    private static final Pattern NAME = Pattern.compile("(.*?)(?:_([a-z]))?");

    /** The program a compute file belongs to, without its letter. */
    public static String base(String path) {
        String bare = path.substring(path.lastIndexOf('/')+1,path.length()-".csh".length());
        var matcher = NAME.matcher(bare);
        if (!matcher.matches()) throw new UnsupportedShaderException("Invalid compute program name: "+path);
        return matcher.group(1);
    }

    public static PackComputeProgram compile(String path, PackPreprocessor preprocessor, ShadercCompiler compiler,
                                             java.util.Map<String,String> imageFormats) throws java.io.IOException {
        String bare = path.substring(path.lastIndexOf('/')+1,path.length()-".csh".length());
        String base = base(path);
        String suffix = bare.substring(base.length());
        CompiledShaderPack.Stage stage = CompiledShaderPack.stage(base);
        if (stage == CompiledShaderPack.Stage.GBUFFER || stage == CompiledShaderPack.Stage.SHADOW) {
            throw new UnsupportedShaderException("Compute programs run between fullscreen passes, not with "+base);
        }
        String source = CompiledShaderPack.lowerSource(preprocessor.process(path,ShaderStage.COMPUTE));
        var translated = new PackProgramTranslator(imageFormats).translateCompute(source);
        SpirvBinary code = compiler.compile(path,translated.source(),ShaderStage.COMPUTE);
        return new PackComputeProgram(path,stage,CompiledShaderPack.ordinal(base),suffix,code,SpirvInterface.activeCompute(translated.bindings(),code),
                translated.localSize(),translated.workGroups(),translated.workGroupsRender());
    }

    /** Groups to dispatch for a screen of the given size. */
    public int[] groups(int width, int height) {
        if (workGroups != null) return workGroups.clone();
        int x = (int)Math.ceil(width*workGroupsRender[0]/localSize[0]), y = (int)Math.ceil(height*workGroupsRender[1]/localSize[1]);
        return new int[]{Math.max(1,x),Math.max(1,y),1};
    }
}
