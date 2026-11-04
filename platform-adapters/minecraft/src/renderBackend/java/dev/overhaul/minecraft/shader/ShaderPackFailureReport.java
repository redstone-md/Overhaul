package dev.overhaul.minecraft.shader;

import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.graph.PackPreflight;
import dev.overhaul.shader.pack.PackDiagnostic;
import dev.overhaul.shader.pack.ShaderPackSource;
import java.io.IOException;
import java.util.Map;

/** Compilation stops at its first failure; only then is it worth compiling again to name every blocker at once. */
final class ShaderPackFailureReport {
    private ShaderPackFailureReport() {}

    static RuntimeException describe(ShaderPackSource source,String dimension,Map<String,Boolean> options,Map<String,String> macros,ShadercCompiler compiler,RuntimeException failure){
        try{
            var blockers=PackPreflight.analyze(source,dimension,options,macros,compiler).diagnostics().stream().filter(PackDiagnostic::blocking).map(PackDiagnostic::toString).toList();
            return blockers.isEmpty()?failure:new IllegalStateException("Shader pack cannot load, "+blockers.size()+" blocker(s):\n"+String.join("\n",blockers),failure);
        }catch(IOException|RuntimeException secondary){failure.addSuppressed(secondary);return failure;}
    }
}
