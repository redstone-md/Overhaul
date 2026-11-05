package dev.overhaul.shader.pack;

/**
 * Numeric literals read from preprocessed pack source. Shaderc's preprocessor emits a unary minus as its own token,
 * so {@code -40.0} arrives as {@code - 40.0}; whitespace is dropped before parsing.
 */
final class PackLiterals {
    private PackLiterals() {
    }

    /** Throws NumberFormatException for anything but one literal, as the JDK parsers do. */
    static float decimal(String text) {
        return Float.parseFloat(compact(text));
    }

    static int integer(String text) {
        return Integer.parseInt(compact(text));
    }

    private static String compact(String text) {
        return text.replaceAll("\\s+", "");
    }
}
