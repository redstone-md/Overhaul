package dev.overhaul.minecraft.smoke;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.commands.FillBiomeCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Disposable-world weather changes use the integrated-server owner and normal biome update packets. */
public final class WeatherFixtures {
    private volatile MixedExpectation mixedExpectation;
    public CompletableFuture<Void> clear(IntegratedServer server){
        Objects.requireNonNull(server,"server");
        return server.submit(()->{
            if(!server.isSameThread())throw new IllegalStateException("Weather fixtures require the server owner");
            server.setWeatherParameters(6000,0,false,false);
        });
    }
    public boolean matchesClearClient(ClientLevel level){
        float rain=Objects.requireNonNull(level,"level").getRainLevel(1);
        return Float.isFinite(rain)&&rain>=0&&rain<=.001f;
    }
    public CompletableFuture<Void> place(IntegratedServer server,BlockPos center,int radius,boolean snow) {
        Objects.requireNonNull(server,"server");validateRadius(radius);
        BlockPos position=Objects.requireNonNull(center,"center").immutable();
        return server.submit(()-> {
            fill(server,position,radius,snow,Integer.MIN_VALUE);
            server.setWeatherParameters(0,6000,true,false);
        });
    }
    public CompletableFuture<Void> mixed(IntegratedServer server,BlockPos center,int radius){
        BlockPos position=Objects.requireNonNull(center,"center").immutable();
        mixedExpectation=null;
        return place(server,position,radius,true).thenCompose(ignored->
                server.submit(()->{
                    fill(server,position,radius,false,boundaryX(position));
                    var level=server.overworld();
                    int diameter=radius*2+1,index=0;
                    Biome.Precipitation[] columns=new Biome.Precipitation[diameter*diameter];
                    BlockPos.MutableBlockPos sample=new BlockPos.MutableBlockPos();
                    for(int z=-radius;z<=radius;z++)for(int x=-radius;x<=radius;x++){
                        sample.set(position.getX()+x,position.getY(),position.getZ()+z);
                        columns[index++]=level.getBiome(sample).value().getPrecipitationAt(sample,level.getSeaLevel());
                    }
                    mixedExpectation=new MixedExpectation(position,radius,columns);
                }));
    }
    public static int boundaryX(BlockPos center){return Math.floorDiv(Objects.requireNonNull(center,"center").getX(),4)*4;}

    private void fill(IntegratedServer server,BlockPos position,int radius,boolean snow,int firstX){
            if(!server.isSameThread())throw new IllegalStateException("Weather fixtures require the server owner");
            var level=server.overworld();
            // Four blocks cover the biome lookup's neighboring quart cells at the weather radius boundary.
            int minX=Math.max(firstX,Math.subtractExact(position.getX(),radius+4)),maxX=Math.addExact(position.getX(),radius+4);
            int minZ=Math.subtractExact(position.getZ(),radius+4),maxZ=Math.addExact(position.getZ(),radius+4);
            int minY=Math.max(level.getMinY(),Math.floorDiv(position.getY()-radius-8,4)*4);
            int maxY=Math.min(level.getMaxY()-1,Math.floorDiv(position.getY()+radius+8,4)*4+3);
            int minChunkX=Math.floorDiv(minX,16),maxChunkX=Math.floorDiv(maxX,16);
            int minChunkZ=Math.floorDiv(minZ,16),maxChunkZ=Math.floorDiv(maxZ,16);
            for(int z=minChunkZ;z<=maxChunkZ;z++)for(int x=minChunkX;x<=maxChunkX;x++)
                if(!level.hasChunk(x,z))throw new IllegalStateException("Weather fixture refuses to load missing chunk "+x+","+z);
            var biome=server.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(snow?Biomes.SNOWY_PLAINS:Biomes.PLAINS);
            // Each strip is at most 16*16*68 blocks: below the normal fill command volume limit.
            for(int z=minChunkZ;z<=maxChunkZ;z++)for(int x=minChunkX;x<=maxChunkX;x++) {
                BlockPos from=new BlockPos(Math.max(minX,x*16),minY,Math.max(minZ,z*16));
                BlockPos to=new BlockPos(Math.min(maxX,x*16+15),maxY,Math.min(maxZ,z*16+15));
                fillStrip(level,from,to,biome);
            }
    }

    private void fillStrip(ServerLevel level,BlockPos from,BlockPos to,Holder<Biome> biome){
        var failure=FillBiomeCommand.fill(level,from,to,biome).right().orElse(null);
        if(failure==null)return;
        if(failure.getType()==FillBiomeCommand.ERROR_NO_BIOMES_SET&&containsBiome(level,from,to,biome))return;
        throw new IllegalStateException("Cannot place weather biome fixture",failure);
    }
    /** Verify the exact stored quart cells used by FillBiomeCommand, without fuzzy block-biome sampling. */
    private boolean containsBiome(ServerLevel level,BlockPos from,BlockPos to,Holder<Biome> biome){
        int minX=QuartPos.fromBlock(from.getX()),maxX=QuartPos.fromBlock(to.getX());
        int minY=QuartPos.fromBlock(from.getY()),maxY=QuartPos.fromBlock(to.getY());
        int minZ=QuartPos.fromBlock(from.getZ()),maxZ=QuartPos.fromBlock(to.getZ());
        for(int z=minZ;z<=maxZ;z++)for(int y=minY;y<=maxY;y++)for(int x=minX;x<=maxX;x++)
            if(!level.getNoiseBiome(x,y,z).is(biome))return false;
        return true;
    }

    /** Verifies actual client packets and weather state; it does not synthesize render columns. */
    public boolean matchesClient(ClientLevel level,BlockPos center,int radius,boolean snow,float minimumRainLevel) {
        return matches(level,center,radius,snow,null,minimumRainLevel);
    }
    public boolean matchesMixedClient(ClientLevel level,BlockPos center,int radius,float minimumRainLevel){
        MixedExpectation expected=mixedExpectation;
        if(expected==null||!expected.matches(center,radius)||!expected.bothTypes)return false;
        return matches(level,center,radius,false,expected,minimumRainLevel);
    }
    private boolean matches(ClientLevel level,BlockPos center,int radius,boolean snow,MixedExpectation mixed,float minimumRainLevel){
        Objects.requireNonNull(level,"level");Objects.requireNonNull(center,"center");validateRadius(radius);
        if(!Float.isFinite(minimumRainLevel)||minimumRainLevel<=0||minimumRainLevel>1)throw new IllegalArgumentException("Minimum rain level must be in (0,1]");
        float rain=level.getRainLevel(1);
        if(!Float.isFinite(rain)||rain<minimumRainLevel)return false;
        int index=0,rainColumns=0,snowColumns=0;
        BlockPos.MutableBlockPos position=new BlockPos.MutableBlockPos();
        for(int z=-radius;z<=radius;z++)for(int x=-radius;x<=radius;x++) {
            position.set(center.getX()+x,center.getY(),center.getZ()+z);
            Biome.Precipitation expected=mixed==null?(snow?Biome.Precipitation.SNOW:Biome.Precipitation.RAIN):mixed.columns[index];
            index++;
            if(!level.hasChunk(Math.floorDiv(position.getX(),16),Math.floorDiv(position.getZ(),16))
                    ||level.getPrecipitationAt(position)!=expected)return false;
            if(expected==Biome.Precipitation.RAIN)rainColumns++;
            if(expected==Biome.Precipitation.SNOW)snowColumns++;
        }
        return mixed==null||rainColumns>0&&snowColumns>0;
    }
    private static final class MixedExpectation{
        private final BlockPos center;
        private final int radius;
        private final Biome.Precipitation[] columns;
        private final boolean bothTypes;
        private MixedExpectation(BlockPos center,int radius,Biome.Precipitation[] columns){
            this.center=center.immutable();this.radius=radius;this.columns=columns.clone();
            boolean rain=false,snow=false;
            for(Biome.Precipitation column:this.columns){
                rain|=column==Biome.Precipitation.RAIN;snow|=column==Biome.Precipitation.SNOW;
            }
            bothTypes=rain&&snow;
        }
        private boolean matches(BlockPos center,int radius){return this.radius==radius&&this.center.equals(center);}
    }
    private static void validateRadius(int radius){if(radius<1||radius>16)throw new IllegalArgumentException("Weather fixture radius must be 1..16");}
}
