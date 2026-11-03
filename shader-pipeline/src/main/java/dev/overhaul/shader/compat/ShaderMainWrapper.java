package dev.overhaul.shader.compat;

import java.util.regex.Pattern;

/** Canonical stage entry-point wrapping preserves all original returns inside the renamed function. */
public final class ShaderMainWrapper {
    private static final Pattern MAIN=Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)\\s*\\{");
    private ShaderMainWrapper(){}
    public static String wrap(String source,String entry,String prologue,String epilogue){
        if(!entry.matches("[A-Za-z_]\\w*")||Pattern.compile("\\b"+Pattern.quote(entry)+"\\b").matcher(source).find())
            throw new UnsupportedShaderException("Reserved shader entry-point name: "+entry);
        var main=MAIN.matcher(source);if(!main.find())throw new UnsupportedShaderException("Shader requires a canonical main entry point");
        int start=main.start(),end=main.end();if(main.find())throw new UnsupportedShaderException("Shader has multiple main definitions");
        return source.substring(0,start)+"void "+entry+"(){"+source.substring(end)+"\nvoid main(){\n"+prologue+"\n"+entry+"();\n"+epilogue+"\n}\n";
    }
}
