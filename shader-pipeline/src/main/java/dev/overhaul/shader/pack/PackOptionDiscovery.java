package dev.overhaul.shader.pack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Finds the options a pack exposes in its GLSL, following Iris OptionAnnotatedSource: a boolean is
 * {@code #define NAME}, default false when line-commented, and counts only if some file tests it
 * with #ifdef or #ifndef. A value option is {@code #define NAME value // [a b c]}. Const options
 * are not discovered, so their defaults stay as written in the source.
 */
public final class PackOptionDiscovery {
    private static final Pattern SOURCE = Pattern.compile(".*\\.(?:vsh|fsh|gsh|csh|tcs|tes|glsl|inc|h)$");
    private static final Pattern DEFINE = Pattern.compile("^\\s*(/{2,}\\s*)?#\\s*define\\s+([A-Za-z_]\\w*)(?![\\w(])\\s*(.*)$");
    private static final Pattern TEST = Pattern.compile("^\\s*#\\s*(?:ifdef|ifndef)\\s+([A-Za-z_]\\w*)");
    private static final Pattern VALUE_LIST = Pattern.compile("\\[[^\\]]*\\]");
    private static final long MAX_SCANNED_CHARACTERS = 67_108_864;

    public record Result(Map<String,Boolean> booleans, Map<String,String> values, List<PackDiagnostic> diagnostics) {
        public Result { booleans = Map.copyOf(booleans); values = Map.copyOf(values); diagnostics = List.copyOf(diagnostics); }
        /** Discovered boolean defaults overlaid with the selection; selected names need not be discovered. */
        public Map<String,Boolean> merged(Map<String,Boolean> selected) {
            Map<String,Boolean> merged = new HashMap<>(booleans); merged.putAll(selected); return merged;
        }
        /** Macro environment for shaders.properties; external macros win over any pack default. */
        public Map<String,String> defines(Map<String,Boolean> selected, Map<String,String> macros) {
            Map<String,String> defines = new HashMap<>(values);
            merged(selected).forEach((name,enabled) -> { if (enabled) defines.put(name,""); });
            defines.putAll(macros);
            return defines;
        }
    }

    private PackOptionDiscovery() {}

    /** Scans sorted paths, so diagnostics and conflict resolution are deterministic. */
    public static Result discover(ShaderPackSource pack, List<String> paths) throws IOException {
        Map<String,Boolean> booleans = new TreeMap<>();
        Map<String,String> values = new TreeMap<>();
        Set<String> tested = new HashSet<>(), conflicted = new HashSet<>();
        List<PackDiagnostic> diagnostics = new ArrayList<>();
        long scanned = 0;
        for (String path : paths) {
            if (!SOURCE.matcher(path).matches()) continue;
            String text;
            try { text = pack.read(path); }
            catch (IOException unreadable) {
                diagnostics.add(PackDiagnostic.degraded(PackDiagnostic.Category.OPTION,path,"Skipped during option discovery: "+unreadable.getMessage()));
                continue;
            }
            scanned += text.length();
            if (scanned > MAX_SCANNED_CHARACTERS) {
                diagnostics.add(PackDiagnostic.degraded(PackDiagnostic.Category.OPTION,path,"Option discovery stopped at its source bound; later files were not scanned"));
                break;
            }
            scan(path,text,booleans,values,tested,conflicted,diagnostics);
        }
        booleans.keySet().retainAll(tested);
        booleans.keySet().removeAll(conflicted); values.keySet().removeAll(conflicted);
        for (String name : booleans.keySet()) if (values.containsKey(name)) {
            diagnostics.add(conflict(name,"both a boolean and a value option"));
            conflicted.add(name);
        }
        booleans.keySet().removeAll(conflicted); values.keySet().removeAll(conflicted);
        return new Result(booleans,values,diagnostics);
    }

    private static void scan(String path, String text, Map<String,Boolean> booleans, Map<String,String> values,
                             Set<String> tested, Set<String> conflicted, List<PackDiagnostic> diagnostics) {
        boolean inBlock = false;
        for (String raw : text.split("\\R",-1)) {
            StringBuilder visible = new StringBuilder();
            inBlock = visibleText(raw,inBlock,visible);
            String line = visible.toString();
            var test = TEST.matcher(line);
            if (test.find()) tested.add(test.group(1));
            var define = DEFINE.matcher(line);
            if (!define.matches()) continue;
            String name = define.group(2), rest = define.group(3).trim();
            boolean commented = define.group(1) != null;
            int comment = rest.indexOf("//");
            String value = (comment < 0 ? rest : rest.substring(0,comment)).trim();
            String annotation = comment < 0 ? "" : rest.substring(comment+2);
            if (value.isEmpty()) {
                Boolean prior = booleans.putIfAbsent(name,!commented);
                if (prior != null && prior == commented && conflicted.add(name)) diagnostics.add(conflict(name,"different boolean defaults, last in "+path));
            } else if (!commented && VALUE_LIST.matcher(annotation).find()) {
                String prior = values.putIfAbsent(name,value);
                if (prior != null && !prior.equals(value) && conflicted.add(name)) diagnostics.add(conflict(name,"different value defaults, last in "+path));
            }
        }
    }

    /** Copies what block comments leave visible; a block opener after a line comment does not count. */
    private static boolean visibleText(String line, boolean startsInBlock, StringBuilder visible) {
        boolean inBlock = startsInBlock;
        int position = 0;
        while (position < line.length()) {
            if (inBlock) {
                int close = line.indexOf("*/",position);
                if (close < 0) return true;
                position = close+2; inBlock = false;
            } else {
                int open = line.indexOf("/*",position), comment = line.indexOf("//",position);
                if (open < 0 || (comment >= 0 && comment < open)) { visible.append(line,position,line.length()); return false; }
                visible.append(line,position,open);
                position = open+2; inBlock = true;
            }
        }
        return inBlock;
    }

    private static PackDiagnostic conflict(String name, String detail) {
        return PackDiagnostic.degraded(PackDiagnostic.Category.OPTION,name,"Not exposed as an option: "+detail);
    }
}
