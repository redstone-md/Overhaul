package foundry.veil.api.client.render.shader.program;

public class ShaderUniformCache {
    public record Uniform(String name, int location, int offset, int type, int arrayLength) {}
    public record UniformBlock(String name, int index, int size, Uniform[] fields) {}
}
