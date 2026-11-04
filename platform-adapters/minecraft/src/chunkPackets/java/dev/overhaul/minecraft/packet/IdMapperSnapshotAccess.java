package dev.overhaul.minecraft.packet;

public interface IdMapperSnapshotAccess {
    IdMapperSnapshot overhaul$identitySnapshot();
    boolean overhaul$isCurrent(IdMapperSnapshot snapshot);
    void overhaul$invalidateIdentitySnapshot(IdMapperSnapshot expected);
    void overhaul$mutateIdentity(Runnable mutation);
}
