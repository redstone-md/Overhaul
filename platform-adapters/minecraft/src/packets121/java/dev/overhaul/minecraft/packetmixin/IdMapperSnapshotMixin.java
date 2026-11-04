package dev.overhaul.minecraft.packetmixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.overhaul.minecraft.packet.IdMapperSnapshot;
import dev.overhaul.minecraft.packet.IdMapperSnapshotAccess;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.IdMapper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** The actual mapper owns one immutable identity snapshot; mutations never edit snapshots retained by packets. */
@Mixin(IdMapper.class)
public abstract class IdMapperSnapshotMixin implements IdMapperSnapshotAccess {
    @Shadow @Final protected List<?> idToT;
    @Unique private final Object overhaul$identityLock=new Object();
    @Unique private volatile IdMapperSnapshot overhaul$identitySnapshot;
    @Unique private int overhaul$identityMutationDepth;

    @Override public IdMapperSnapshot overhaul$identitySnapshot(){
        var current=overhaul$identitySnapshot;if(current!=null)return current;
        synchronized(overhaul$identityLock){
            if(overhaul$identityMutationDepth!=0)return null;
            current=overhaul$identitySnapshot;
            if(current==null){current=IdMapperSnapshot.copyOf(idToT);overhaul$identitySnapshot=current;}
            return current;
        }
    }
    @Override public boolean overhaul$isCurrent(IdMapperSnapshot snapshot){return snapshot!=null&&overhaul$identitySnapshot==snapshot;}
    @Override public void overhaul$invalidateIdentitySnapshot(IdMapperSnapshot expected){
        synchronized(overhaul$identityLock){if(overhaul$identitySnapshot==expected)overhaul$identitySnapshot=null;}
    }
    @Override public void overhaul$mutateIdentity(Runnable mutation){
        Objects.requireNonNull(mutation);
        synchronized(overhaul$identityLock){
            overhaul$identityMutationDepth++;overhaul$identitySnapshot=null;
            try{mutation.run();}
            finally{overhaul$identitySnapshot=null;overhaul$identityMutationDepth--;}
        }
    }
    @WrapMethod(method="addMapping(Ljava/lang/Object;I)V")
    private void overhaul$mapping(Object value,int id,Operation<Void> original){overhaul$mutateIdentity(()->original.call(value,id));}
    @WrapMethod(method="add(Ljava/lang/Object;)V")
    private void overhaul$add(Object value,Operation<Void> original){overhaul$mutateIdentity(()->original.call(value));}
}
