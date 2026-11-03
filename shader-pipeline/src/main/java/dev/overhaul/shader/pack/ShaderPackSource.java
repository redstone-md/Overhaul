package dev.overhaul.shader.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads only shaders/ entries; ZIP contents are never extracted to disk. */
public final class ShaderPackSource implements AutoCloseable {
    private static final int MAX_SOURCE_BYTES = 1_048_576;
    private final Path directory;
    private final ZipFile archive;
    private boolean closed;

    private ShaderPackSource(Path directory, ZipFile archive) {
        this.directory = directory; this.archive = archive;
    }

    public static ShaderPackSource open(Path pack) throws IOException {
        if (Files.isDirectory(pack)) return new ShaderPackSource(pack.toRealPath(), null);
        ZipFile zip = new ZipFile(pack.toFile(), StandardCharsets.UTF_8);
        try {
            Set<String> seen = new HashSet<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!seen.add(entry.getName())) throw new IOException("Duplicate ZIP entry: " + entry.getName());
            }
            return new ShaderPackSource(null, zip);
        } catch (IOException | RuntimeException failure) {
            zip.close();
            throw failure;
        }
    }

    public synchronized String read(String shaderPath) throws IOException {
        if (closed) throw new IOException("Shader pack is closed");
        String normalized = normalize(shaderPath);
        if (archive != null) {
            ZipEntry entry = archive.getEntry("shaders/" + normalized);
            if (entry == null || entry.isDirectory()) throw new IOException("Missing shader: " + normalized);
            if (entry.getSize() > MAX_SOURCE_BYTES) throw new IOException("Shader source too large: " + normalized);
            try (InputStream input = archive.getInputStream(entry)) { return readLimited(input, normalized); }
        }
        Path shaderRoot = directory.resolve("shaders").toRealPath();
        if (!shaderRoot.startsWith(directory)) throw new IOException("Shader root escapes pack");
        Path source = shaderRoot.resolve(normalized).toRealPath();
        if (!source.startsWith(shaderRoot)) throw new IOException("Shader escapes pack: " + shaderPath);
        try (InputStream input = Files.newInputStream(source)) { return readLimited(input, normalized); }
    }

    private static String readLimited(InputStream input, String name) throws IOException {
        byte[] bytes = input.readNBytes(MAX_SOURCE_BYTES + 1);
        if (bytes.length > MAX_SOURCE_BYTES) throw new IOException("Shader source too large: " + name);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static String normalize(String name) throws IOException {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf(':') >= 0) throw new IOException("Invalid shader path: " + name);
        String relative = name.startsWith("/") ? name.substring(1) : name;
        Path normalized = Path.of(relative).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..") || normalized.toString().isEmpty()) {
            throw new IOException("Shader path escapes root: " + name);
        }
        return normalized.toString().replace('\\', '/');
    }

    @Override public synchronized void close() throws IOException {
        if (!closed) { closed = true; if (archive != null) archive.close(); }
    }
}
