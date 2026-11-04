package dev.overhaul.shader.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads only shaders/ entries; ZIP contents are never extracted to disk. */
public final class ShaderPackSource implements AutoCloseable {
    private static final int MAX_SOURCE_BYTES = 1_048_576;
    private final Path directory;
    private final ZipFile archive;
    private final java.util.Map<String,byte[]> packaged;
    private boolean closed;

    private ShaderPackSource(Path directory, ZipFile archive) {
        this(directory,archive,null);
    }
    private ShaderPackSource(Path directory,ZipFile archive,java.util.Map<String,byte[]> packaged){this.directory=directory;this.archive=archive;this.packaged=packaged;}

    /** Shipped production packs use the same bounded catalog/preprocessor without extracting temporary files. */
    public static ShaderPackSource packaged(Class<?> owner,String root,List<String> names)throws IOException{
        if(!root.startsWith("/")||root.contains("..")||names.size()>64)throw new IllegalArgumentException("Invalid packaged shader root");
        java.util.Map<String,byte[]> data=new java.util.LinkedHashMap<>();long total=0;
        for(String name:names){
            String path=normalize(name);if(data.containsKey(path))throw new IOException("Duplicate packaged shader: "+path);
            try(InputStream input=owner.getResourceAsStream(root+"/"+path)){
                if(input==null)throw new IOException("Missing packaged shader: "+path);
                byte[] bytes=readLimited(input,path,MAX_SOURCE_BYTES);total+=bytes.length;if(total>16_777_216)throw new IOException("Packaged shader source bound exceeded");data.put(path,bytes);
            }
        }
        return new ShaderPackSource(null,null,java.util.Map.copyOf(data));
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
        return new String(readBytes(shaderPath,MAX_SOURCE_BYTES),StandardCharsets.UTF_8);
    }

    /** Bounded assets share exactly the source path and archive-containment rules. */
    public synchronized byte[] readBytes(String shaderPath,int maximumBytes) throws IOException {
        if (closed) throw new IOException("Shader pack is closed");
        if(maximumBytes<1||maximumBytes>33_554_432)throw new IllegalArgumentException("Asset read bound exceeds [1,32MiB]");
        String normalized = normalize(shaderPath);
        if(packaged!=null){byte[] bytes=packaged.get(normalized);if(bytes==null)throw new IOException("Missing packaged shader: "+normalized);if(bytes.length>maximumBytes)throw new IOException("Packaged shader asset too large");return bytes.clone();}
        if (archive != null) {
            ZipEntry entry = archive.getEntry("shaders/" + normalized);
            if (entry == null || entry.isDirectory()) throw new IOException("Missing shader: " + normalized);
            if (entry.getSize() > maximumBytes) throw new IOException("Shader asset too large: " + normalized);
            try (InputStream input = archive.getInputStream(entry)) { return readLimited(input, normalized,maximumBytes); }
        }
        Path shaderRoot = directory.resolve("shaders").toRealPath();
        if (!shaderRoot.startsWith(directory)) throw new IOException("Shader root escapes pack");
        Path source = shaderRoot.resolve(normalized).toRealPath();
        if (!source.startsWith(shaderRoot)) throw new IOException("Shader escapes pack: " + shaderPath);
        try (InputStream input = Files.newInputStream(source)) { return readLimited(input, normalized,maximumBytes); }
    }

    /** Sorted paths relative to shaders/, preserving directories with a trailing slash. */
    public synchronized List<String> paths() throws IOException {
        if (closed) throw new IOException("Shader pack is closed");
        Set<String> paths = new TreeSet<>();
        if(packaged!=null)return packaged.keySet().stream().sorted().toList();
        if (archive != null) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().startsWith("shaders/")) continue;
                String path = entry.getName().substring(8);
                if (path.isEmpty()) continue;
                boolean directory = entry.isDirectory();
                String canonical = normalize(path);
                if (!path.equals(canonical+(directory ? "/" : ""))) throw new IOException("Noncanonical ZIP shader path: "+path);
                paths.add(path);
                for (int slash=path.indexOf('/');slash>=0;slash=path.indexOf('/',slash+1)) paths.add(path.substring(0,slash+1));
                if (paths.size() > 65536) throw new IOException("Too many shader entries");
            }
        } else {
            Path root = directory.resolve("shaders").toRealPath();
            if (!root.startsWith(directory)) throw new IOException("Shader root escapes pack");
            try (var stream = Files.walk(root)) {
                var iterator = stream.iterator();
                while (iterator.hasNext()) {
                    Path path = iterator.next();
                    if (path.equals(root)) continue;
                    if (Files.isSymbolicLink(path)) throw new IOException("Symlink in shader pack: "+path);
                    paths.add(root.relativize(path).toString().replace('\\','/')+(Files.isDirectory(path) ? "/" : ""));
                    if (paths.size() > 65536) throw new IOException("Too many shader entries");
                }
            }
        }
        return List.copyOf(paths);
    }

    private static byte[] readLimited(InputStream input, String name,int maximumBytes) throws IOException {
        byte[] bytes = input.readNBytes(maximumBytes + 1);
        if (bytes.length > maximumBytes) throw new IOException("Shader asset too large: " + name);
        return bytes;
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
