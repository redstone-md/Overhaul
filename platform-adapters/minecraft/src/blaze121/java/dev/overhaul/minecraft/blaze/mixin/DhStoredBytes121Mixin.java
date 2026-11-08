package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeDhBytes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.io.ByteArrayInputStream;

/**
 * Every stored LOD is decoded through a data stream over a byte array stream that only the decoding thread sees, one
 * byte at a time. The array stream is made as one that reads without taking its monitor (see {@link BlazeDhBytes}).
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream", remap = false)
abstract class DhStoredBytes121Mixin {
    @WrapOperation(method = "create([BLcom/seibel/distanthorizons/api/enums/config/EDhApiDataCompressionMode;Lcom/seibel/distanthorizons/core/util/objects/pooling/PhantomArrayList/PhantomArrayListCheckout;)Lcom/seibel/distanthorizons/core/util/objects/dataStreams/DhDataInputStream;",
        require = 0, at = @At(value = "NEW", target = "([BII)Ljava/io/ByteArrayInputStream;"))
    private static ByteArrayInputStream overhaul$unsharedRange(byte[] buffer, int offset, int length, Operation<ByteArrayInputStream> make) {
        return new BlazeDhBytes.Unshared(buffer, offset, length);
    }

    @WrapOperation(method = "create([BLcom/seibel/distanthorizons/api/enums/config/EDhApiDataCompressionMode;Lcom/seibel/distanthorizons/core/util/objects/pooling/PhantomArrayList/PhantomArrayListCheckout;)Lcom/seibel/distanthorizons/core/util/objects/dataStreams/DhDataInputStream;",
        require = 0, at = @At(value = "NEW", target = "([B)Ljava/io/ByteArrayInputStream;"))
    private static ByteArrayInputStream overhaul$unshared(byte[] buffer, Operation<ByteArrayInputStream> make) {
        return new BlazeDhBytes.Unshared(buffer);
    }
}
