package dev.overhaul.minecraft.packet;

import java.util.List;

/** Immutable identity table, including gaps, shared by packets decoded against one mapper revision. */
public final class IdMapperSnapshot {
    private final Object[] values;
    private IdMapperSnapshot(Object[] values){this.values=values;}
    public static IdMapperSnapshot copyOf(List<?> source){return new IdMapperSnapshot(source.toArray());}
    public Object byId(int id){return id>=0&&id<values.length?values[id]:null;}
}
