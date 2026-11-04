package dev.overhaul.shader.pack;

import java.util.Objects;

/**
 * One explicit statement about a pack feature. BLOCKING means the pack cannot run as authored,
 * DEGRADED means it runs without that feature, and INFO records a decision worth seeing.
 */
public record PackDiagnostic(Severity severity, Category category, String subject, String message) {
    public enum Severity { BLOCKING, DEGRADED, INFO }
    public enum Category { PROPERTY, OPTION, PROGRAM, STAGE, UNIFORM, SAMPLER, ATTRIBUTE, FORMAT }

    public PackDiagnostic {
        Objects.requireNonNull(severity); Objects.requireNonNull(category);
        Objects.requireNonNull(subject); Objects.requireNonNull(message);
    }
    public static PackDiagnostic blocking(Category category, String subject, String message) {
        return new PackDiagnostic(Severity.BLOCKING,category,subject,message);
    }
    public static PackDiagnostic degraded(Category category, String subject, String message) {
        return new PackDiagnostic(Severity.DEGRADED,category,subject,message);
    }
    public static PackDiagnostic info(Category category, String subject, String message) {
        return new PackDiagnostic(Severity.INFO,category,subject,message);
    }
    public boolean blocking() { return severity == Severity.BLOCKING; }
    @Override public String toString() { return severity+" "+category+" "+subject+": "+message; }
}
