package dev.overhaul.adapter;

/** Section coordinates inside one world-scoped bridge. */
public final class SectionKey {
    private final int x, y, z;

    public SectionKey(int x, int y, int z) { this.x=x; this.y=y; this.z=z; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    @Override public boolean equals(Object other) {
        if (!(other instanceof SectionKey)) return false;
        SectionKey key=(SectionKey) other;
        return x==key.x && y==key.y && z==key.z;
    }
    @Override public int hashCode() { return (x*31+y)*31+z; }
}
