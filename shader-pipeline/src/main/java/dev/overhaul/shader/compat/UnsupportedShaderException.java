package dev.overhaul.shader.compat;

/** Semantic compatibility failure, deliberately distinct from a native compiler error. */
public final class UnsupportedShaderException extends IllegalArgumentException {
    public UnsupportedShaderException(String message) { super(message); }
}
