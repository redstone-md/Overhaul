package dev.overhaul.shader.compile;

import org.lwjgl.util.shaderc.ShadercIncludeResolve;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultRelease;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** Owns native callback allocations until shaderc explicitly releases each include. */
final class ShaderIncludes implements AutoCloseable {
    private final Map<Long,Allocation> live = new HashMap<>();
    private final ShadercCompiler.IncludeResolver resolver;
    private int totalBytes;
    final ShadercIncludeResolve resolve;
    final ShadercIncludeResultRelease release;

    ShaderIncludes(ShadercCompiler.IncludeResolver resolver) {
        this.resolver = resolver;
        resolve = ShadercIncludeResolve.create(this::resolve);
        release = ShadercIncludeResultRelease.create((user,pointer) -> release(pointer));
    }

    private long resolve(long user, long requested, int kind, long requester, long depth) {
        String name, text;
        try {
            if (resolver == null) throw new IllegalArgumentException("No include resolver was supplied");
            if (depth > 32) throw new IllegalArgumentException("Include depth exceeds 32");
            var include = resolver.resolve(memUTF8(requested),memUTF8(requester),kind == shaderc_include_type_relative);
            name = include.name(); text = include.text();
            if (text.length() > 1_048_576 || (long) totalBytes+text.length() > 4_194_304) {
                throw new IllegalArgumentException("Expanded includes exceed size limit");
            }
            totalBytes += text.length();
        } catch (Exception failure) {
            // Shaderc interprets an empty source name as an include failure.
            name = ""; text = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        }
        ByteBuffer sourceName = memUTF8(name,false), content = memUTF8(text,false);
        ShadercIncludeResult result = ShadercIncludeResult.calloc().source_name(sourceName).content(content);
        live.put(result.address(),new Allocation(result,sourceName,content));
        return result.address();
    }

    private void release(long pointer) {
        Allocation allocation = live.remove(pointer);
        if (allocation != null) allocation.close();
    }

    @Override public void close() {
        live.values().forEach(Allocation::close); live.clear();
        release.free(); resolve.free();
    }
    private record Allocation(ShadercIncludeResult result, ByteBuffer name, ByteBuffer content) {
        void close() { result.free(); memFree(content); memFree(name); }
    }
}
