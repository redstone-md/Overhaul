package dev.overhaul.minecraft.blaze;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;

/**
 * Sky and fog colours each sample 216 biomes around the camera every frame. Those lookups change only when the camera
 * crosses a quart or a chunk's biomes change, so one lookup per cell and client tick is enough.
 */
public final class BlazeBiomeSamples {
    private static final int CELLS=512;
    private static final long[] KEYS=new long[CELLS];
    private static final int[] STAMPS=new int[CELLS];
    @SuppressWarnings("unchecked")
    private static final Holder<Biome>[] BIOMES=new Holder[CELLS];
    private static BiomeManager owner;
    private static int tick=1;
    private BlazeBiomeSamples(){}

    public static void nextTick(){tick++;}

    public static Holder<Biome> at(BiomeManager manager,int x,int y,int z,Operation<Holder<Biome>> lookup){
        if(!RenderSystem.isOnRenderThread())return lookup.call(manager,x,y,z);
        if(manager!=owner){owner=manager;tick++;}
        int cell=x&7|(y&7)<<3|(z&7)<<6;
        long key=(long)x<<42^(y&0x1FFFFFL)<<21^z&0x1FFFFFL;
        if(STAMPS[cell]==tick&&KEYS[cell]==key)return BIOMES[cell];
        Holder<Biome> biome=lookup.call(manager,x,y,z);
        STAMPS[cell]=tick;KEYS[cell]=key;BIOMES[cell]=biome;
        return biome;
    }
}
