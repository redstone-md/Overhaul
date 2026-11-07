package dev.overhaul.minecraft.blaze;

/** Remembers per class whether it or one of its superclasses lives in a package, for listeners that only act on one mod's entities. */
public final class BlazePackageClasses extends ClassValue<Boolean> {
    private final String prefix;

    /** {@code prefix} is a package name with its trailing dot. */
    public BlazePackageClasses(String prefix) {
        this.prefix = prefix;
    }

    public boolean contains(Object value) {
        return value != null && get(value.getClass());
    }

    @Override
    protected Boolean computeValue(Class<?> type) {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) if (at.getName().startsWith(prefix)) return true;
        return false;
    }
}
