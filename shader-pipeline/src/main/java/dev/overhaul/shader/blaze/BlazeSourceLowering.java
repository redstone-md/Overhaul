package dev.overhaul.shader.blaze;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;

/** Parses only global stage declarations; function parameter in/out qualifiers stay untouched. */
final class BlazeSourceLowering {
    private static final Pattern DECLARATION = Pattern.compile("(?:(flat)\\s+)?(in|out)\\s+(\\w+)\\s+(\\w+)\\s*;");
    private BlazeSourceLowering() {}
    record Stage(String source,Set<String> flatVaryings,Map<String,String> attributes,Map<String,String> varyings){}

    static Stage lower(String source, boolean vertex) {
        String text = source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", "");
        StringBuilder output = new StringBuilder(text.length());
        int braces = 0, parentheses = 0;
        String fragmentOutput = null;
        Set<String> flatVaryings=new HashSet<>();
        Map<String,String> attributes=new LinkedHashMap<>(),varyings=new LinkedHashMap<>();
        for (int index = 0; index < text.length();) {
            char value = text.charAt(index);
            if (Character.isJavaIdentifierStart(value)) {
                int end = index + 1;
                while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
                String word = text.substring(index, end);
                if (braces == 0 && parentheses == 0 && (word.equals("in") || word.equals("out") || word.equals("flat"))) {
                    Matcher declaration = DECLARATION.matcher(text).region(index, text.length());
                    if (!declaration.lookingAt()) throw new UnsupportedShaderException("Unsupported Blaze3D stage declaration");
                    boolean flat=declaration.group(1)!=null;
                    String direction=declaration.group(2),type = declaration.group(3), name = declaration.group(4);
                    if(flat){
                        if(vertex&&!direction.equals("out")||!vertex&&!direction.equals("in"))throw new UnsupportedShaderException("Flat qualifier on non-varying input/output");
                        flatVaryings.add(name);
                    }
                    if (!vertex && direction.equals("out")) {
                        if (fragmentOutput != null || !type.equals("vec4")) {
                            throw new UnsupportedShaderException("Blaze3D multiple/non-vec4 fragment outputs require explicit lowering");
                        }
                        fragmentOutput = name;
                    } else {
                        boolean attribute=vertex&&direction.equals("in");
                        if(!attribute&&!flat&&integer(type))throw new UnsupportedShaderException("Integer varying must be flat: "+name);
                        Map<String,String> declarations=attribute?attributes:varyings;
                        if(declarations.putIfAbsent(name,type)!=null)throw new UnsupportedShaderException("Duplicate stage declaration: "+name);
                        output.append(attribute ? "attribute " : "varying ")
                                .append(surrogate(type)).append(' ').append(name).append(';');
                    }
                    index = declaration.end();
                    continue;
                }
                if (braces == 0 && parentheses == 0 && (word.equals("smooth")
                        || word.equals("noperspective") || word.equals("layout") || word.equals("centroid"))) {
                    throw new UnsupportedShaderException("Blaze3D qualified stage declaration requires explicit lowering: " + word);
                }
                output.append(word);
                index = end;
                continue;
            }
            if (value == '{') braces++;
            else if (value == '}') braces--;
            else if (value == '(') parentheses++;
            else if (value == ')') parentheses--;
            if (braces < 0 || parentheses < 0) throw new UnsupportedShaderException("Unbalanced Blaze3D shader source");
            output.append(value);
            index++;
        }
        if (braces != 0 || parentheses != 0) throw new UnsupportedShaderException("Unbalanced Blaze3D shader source");
        if (!vertex && fragmentOutput == null) throw new UnsupportedShaderException("Missing Blaze3D fragment output");
        String lowered=fragmentOutput == null ? output.toString() : output.toString().replaceAll(
                "\\b" + Pattern.quote(fragmentOutput) + "\\b", "gl_FragColor");
        return new Stage(lowered,Set.copyOf(flatVaryings),Map.copyOf(attributes),Map.copyOf(varyings));
    }

    static String surrogate(String type){
        if(type.equals("int")||type.equals("uint"))return "float";
        if(type.matches("[iu]vec[234]"))return type.substring(1);
        if(type.equals("float")||type.matches("vec[234]")||matrix(type))return type;
        throw new UnsupportedShaderException("Unsupported Blaze3D stage type: "+type);
    }
    /** The type a varying is declared with while locations are assigned; a matrix keeps its type so each column gets a location. */
    static String schemaType(String type){return matrix(type)?type:"vec4";}
    private static boolean matrix(String type){return type.matches("mat[234]");}
    private static boolean integer(String type){return type.equals("int")||type.equals("uint")||type.startsWith("ivec")||type.startsWith("uvec");}
}
