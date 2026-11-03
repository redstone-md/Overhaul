package dev.overhaul.minecraft.smoke;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Actual server entities, menu-open events and received client state; no constructed model submissions. */
final class ModelFixtures {
    static final BlockPos CHEST=new BlockPos(29,4,0);
    static final double CAMERA_X=28,CAMERA_Y=4,CAMERA_Z=-3;
    static final float CAMERA_YAW=0,CAMERA_PITCH=12;
    private record Identity(int visibleId,UUID visibleUuid,int translucentId,UUID translucentUuid){}
    private final BlockState chestState=Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH);
    private volatile Identity identity;
    // Server-only references are consumed exclusively inside submitted server actions.
    private Pig visible,translucent;
    private volatile BlockState previousBlock;
    private volatile GameType previousMode;
    private boolean previousNoGravity,opened;
    private volatile boolean placed,cleared,restored;
    private volatile String lastServerThread="not placed";
    private volatile int menuId=-1;
    private volatile Map<String,Object> serverInvisibility=Map.of();
    private volatile Map<String,Object> navigationProof=Map.of();
    private int navigationStartTick=-1,lastNavigationAttempt=-1,navigationAttempts;
    private long navigationStartTime;
    private boolean navigationAccepted;

    CompletableFuture<Void> place(IntegratedServer server,UUID playerId){
        return server.submit(()->{
            requireOwner(server);var level=server.overworld();var player=player(server,playerId);
            if(placed)throw new IllegalStateException("Model fixture is already placed");
            if(!level.hasChunk(1,0)||!level.hasChunk(1,-1))throw new IllegalStateException("Model fixture refuses to load missing scene chunks");
            previousBlock=level.getBlockState(CHEST);
            if(!previousBlock.isAir())throw new IllegalStateException("Model fixture refuses to replace an existing block at "+CHEST);
            previousMode=player.gameMode();previousNoGravity=player.isNoGravity();
            if(!level.setBlock(CHEST,chestState,Block.UPDATE_ALL)||!(level.getBlockEntity(CHEST) instanceof ChestBlockEntity))
                throw new IllegalStateException("Actual chest block entity was not created");
            placed=true;
            visible=createPig(server,26,false);translucent=createPig(server,31,true);
            identity=new Identity(visible.getId(),visible.getUUID(),translucent.getId(),translucent.getUUID());
            verifyInvisibility();
            camera(player,true);
        });
    }
    CompletableFuture<Void> animate(IntegratedServer server,UUID playerId){
        return server.submit(()->{
            requireOwner(server);var player=player(server,playerId);camera(player,false);
            visible.setNoAi(false);
            navigationStartTick=visible.tickCount;navigationStartTime=server.overworld().getGameTime();
            verifyInvisibility();
            updateNavigationProof();
            if(!(server.overworld().getBlockEntity(CHEST) instanceof ChestBlockEntity chest))throw new IllegalStateException("Owned chest is absent");
            var menu=player.openMenu(chest);
            if(menu.isEmpty())throw new IllegalStateException("Actual server chest menu did not open");
            menuId=menu.getAsInt();
            opened=true;
        });
    }
    CompletableFuture<Boolean> advanceNavigation(IntegratedServer server){
        return server.submit(()->{
            requireOwner(server);
            if(visible==null||visible.isRemoved()||navigationStartTick<0)throw new IllegalStateException("Animation navigation has no live initialized pig");
            boolean canUpdate=visible.onGround()||visible.isInLiquid()||visible.isPassenger();
            if(!navigationAccepted&&visible.tickCount>navigationStartTick&&server.overworld().getGameTime()>navigationStartTime
                    &&visible.tickCount!=lastNavigationAttempt&&canUpdate){
                lastNavigationAttempt=visible.tickCount;navigationAttempts++;
                navigationAccepted=visible.getNavigation().moveTo(26,4,7,.8);updateNavigationProof();
                java.util.logging.Logger.getLogger("overhaul").info("Model fixture actual navigation admission: "+navigationProof);
            }else updateNavigationProof();
            if(!navigationAccepted&&(navigationAttempts>=5||visible.tickCount-navigationStartTick>80))
                throw new IllegalStateException("Vanilla pig navigation admission failed after real physics ticks: "+navigationProof);
            return navigationAccepted;
        });
    }
    int openedMenuId(){return menuId;}
    CompletableFuture<Map<String,Object>> verifyPersistentInvisibility(IntegratedServer server){
        return server.submit(()->{requireOwner(server);verifyInvisibility();if(navigationStartTick>=0)updateNavigationProof();return serverInvisibility;});
    }
    CompletableFuture<Map<String,Object>> verifyOpen(IntegratedServer server,UUID playerId){
        return server.submit(()->{
            requireOwner(server);var player=player(server,playerId);var level=server.overworld();
            if(!(player.containerMenu instanceof ChestMenu menu)||menu.containerId!=menuId||menu.getContainer()!=level.getBlockEntity(CHEST))
                throw new IllegalStateException("Hidden client GUI lost its actual server chest menu");
            int count=ChestBlockEntity.getOpenCount(level,CHEST);
            if(count<=0)throw new IllegalStateException("Actual chest opener count became empty while hiding its GUI");
            verifyInvisibility();
            return Map.<String,Object>of("serverMenuId",menu.containerId,"serverOpenCount",count,"serverGameTime",level.getGameTime(),"serverThread",Thread.currentThread().getName());
        });
    }
    CompletableFuture<Void> translucentView(IntegratedServer server,UUID playerId){
        return server.submit(()->{
            requireOwner(server);var player=player(server,playerId);
            if(cleared)return;
            // Closing while Creative lets ChestBlockEntity.stopOpen decrement its actual opener counter.
            if(opened){player.closeContainer();opened=false;}
            visible.getNavigation().stop();visible.setNoAi(true);visible.setDeltaMovement(0,0,0);
            verifyInvisibility();
            camera(player,true);
        });
    }
    CompletableFuture<Void> clear(IntegratedServer server,UUID playerId){
        return server.submit(()->{
            requireOwner(server);var player=player(server,playerId);
            if(cleared)return;
            if(opened){player.closeContainer();opened=false;}
            if(visible!=null&&!visible.isRemoved())visible.discard();
            if(translucent!=null&&!translucent.isRemoved())translucent.discard();
            if(placed){
                if(!server.overworld().getBlockState(CHEST).equals(chestState))throw new IllegalStateException("Owned chest state changed before teardown");
                server.overworld().setBlock(CHEST,previousBlock,Block.UPDATE_ALL);
            }
            cleared=true;
            if(identity!=null&&(server.overworld().getEntity(identity.visibleId())!=null||server.overworld().getEntity(identity.translucentId())!=null))
                throw new IllegalStateException("Removed model fixtures remain in the authoritative server lookup");
        });
    }
    CompletableFuture<Void> restorePlayer(IntegratedServer server,UUID playerId){
        return server.submit(()->{
            requireOwner(server);var player=player(server,playerId);
            if(previousMode!=null){
                player.setGameMode(previousMode);player.setNoGravity(previousNoGravity);
                if(player.gameMode()!=previousMode||player.isNoGravity()!=previousNoGravity)throw new IllegalStateException("Model fixture player restoration failed");
            }
            restored=true;
        });
    }
    boolean restoreMatches(LocalPlayer player){return restored&&previousMode!=null&&player.isSpectator()==(previousMode==GameType.SPECTATOR)
            &&player.isCreative()==(previousMode==GameType.CREATIVE)&&player.isNoGravity()==previousNoGravity;}
    boolean matches(ClientLevel level,LocalPlayer player,int stage){
        if(identity==null)return false;
        if(stage==4)return cleared&&level.getEntity(identity.visibleId())==null&&level.getEntity(identity.translucentId())==null
                &&level.getBlockState(CHEST).equals(previousBlock)&&level.getBlockEntity(CHEST)==null;
        var pig=visible(level);var hidden=translucent(level);
        return pig!=null&&hidden!=null&&hidden.isInvisible()&&!pig.isInvisible()&&level.getBlockState(CHEST).equals(chestState)
                &&level.getBlockEntity(CHEST) instanceof ChestBlockEntity&&player.isSpectator()==(stage!=1)
                &&(stage==1||!hidden.isInvisibleTo(player));
    }
    Pig visible(ClientLevel level){
        if(identity==null)return null;var entity=level.getEntity(identity.visibleId());
        return entity instanceof Pig pig&&pig.getUUID().equals(identity.visibleUuid())?pig:null;
    }
    private Pig translucent(ClientLevel level){
        if(identity==null)return null;var entity=level.getEntity(identity.translucentId());
        return entity instanceof Pig pig&&pig.getUUID().equals(identity.translucentUuid())?pig:null;
    }
    float chestOpenness(ClientLevel level){return level.getBlockEntity(CHEST) instanceof ChestBlockEntity chest?chest.getOpenNess(1):0;}
    Map<String,Object> snapshot(ClientLevel level,LocalPlayer player){
        var values=new LinkedHashMap<String,Object>();values.put("serverMutationThread",lastServerThread);values.put("placed",placed);
        values.put("cleared",cleared);values.put("playerModeRestored",restored);values.put("chestPosition",List.of(CHEST.getX(),CHEST.getY(),CHEST.getZ()));
        values.put("chestOpenness",chestOpenness(level));values.put("clientSpectator",player.isSpectator());
        values.put("clientCreative",player.isCreative());values.put("clientNoGravity",player.isNoGravity());
        values.put("originalGameMode",previousMode==null?"unrecorded":previousMode.toString());values.put("originalNoGravity",previousNoGravity);
        values.put("clientRestorationMatches",restoreMatches(player));
        if(identity!=null){values.put("visibleId",identity.visibleId());values.put("visibleUuid",identity.visibleUuid().toString());
            values.put("translucentId",identity.translucentId());values.put("translucentUuid",identity.translucentUuid().toString());}
        var pig=visible(level);values.put("visibleClientPresent",pig!=null);
        if(pig!=null){values.put("walkPosition",pig.walkAnimation.position());values.put("walkSpeed",pig.walkAnimation.speed());values.put("pigPosition",List.of(pig.getX(),pig.getY(),pig.getZ()));}
        var hidden=translucent(level);values.put("translucentClientPresent",hidden!=null);
        values.put("serverInvisibility",serverInvisibility);
        values.put("serverNavigation",navigationProof);
        values.put("clientInvisibilityEffect",hidden!=null&&hidden.hasEffect(MobEffects.INVISIBILITY));
        values.put("clientInvisibleFlag",hidden!=null&&hidden.isInvisible());
        var effect=hidden==null?null:hidden.getEffect(MobEffects.INVISIBILITY);
        values.put("clientInfiniteInvisibility",effect!=null&&effect.isInfiniteDuration());
        values.put("clientEffectListRequired",false);
        values.put("translucentVisibleToCamera",hidden!=null&&hidden.isInvisible()&&!hidden.isInvisibleTo(player));
        return Map.copyOf(values);
    }
    private Pig createPig(IntegratedServer server,double x,boolean invisible){
        var pig=EntityTypes.PIG.create(server.overworld(),EntitySpawnReason.COMMAND);
        if(pig==null)throw new IllegalStateException("Actual pig creation failed");
        pig.setPos(x,4,2);pig.setYRot(180);pig.setYHeadRot(180);pig.setNoAi(true);pig.setPersistenceRequired();
        if(invisible&&!pig.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY,MobEffectInstance.INFINITE_DURATION,0,false,false,false)))
            throw new IllegalStateException("Actual persistent pig invisibility effect was rejected");
        // Match the effect immediately before tracking starts; normal living ticks maintain this flag from active effects.
        pig.setInvisible(invisible);
        if(!server.overworld().addFreshEntity(pig))throw new IllegalStateException("Actual pig registration failed");
        return pig;
    }
    private void verifyInvisibility(){
        var effect=translucent.getEffect(MobEffects.INVISIBILITY);
        if(effect==null||!effect.isInfiniteDuration()||!translucent.isInvisible())throw new IllegalStateException("Server pig lost its persistent invisibility effect or hidden flag");
        serverInvisibility=Map.of("active",translucent.hasEffect(MobEffects.INVISIBILITY),"infinite",effect.isInfiniteDuration(),
                "hiddenFlag",translucent.isInvisible(),"entityTicks",translucent.tickCount,
                "serverGameTime",translucent.level().getGameTime(),"serverThread",Thread.currentThread().getName());
    }
    private void updateNavigationProof(){
        var level=visible.level();var position=visible.blockPosition();var path=visible.getNavigation().getPath();
        var proof=new LinkedHashMap<String,Object>();proof.put("serverThread",Thread.currentThread().getName());proof.put("serverGameTime",level.getGameTime());
        proof.put("position",List.of(visible.getX(),visible.getY(),visible.getZ()));proof.put("deltaMovement",List.of(visible.getDeltaMovement().x,visible.getDeltaMovement().y,visible.getDeltaMovement().z));
        proof.put("entityTicks",visible.tickCount);proof.put("enabledAtTick",navigationStartTick);proof.put("enabledAtGameTime",navigationStartTime);
        proof.put("onGround",visible.onGround());proof.put("inLiquid",visible.isInLiquid());proof.put("passenger",visible.isPassenger());
        proof.put("canUpdateGroundPath",visible.onGround()||visible.isInLiquid()||visible.isPassenger());proof.put("noAi",visible.isNoAi());proof.put("noGravity",visible.isNoGravity());
        proof.put("currentBlock",level.getBlockState(position).toString());proof.put("belowBlock",level.getBlockState(position.below()).toString());
        proof.put("groundSurfaceY",level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,position.getX(),position.getZ()));
        proof.put("targetSurfaceY",level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,26,7));proof.put("target",List.of(26,4,7));
        proof.put("stableDestination",visible.getNavigation().isStableDestination(new BlockPos(26,4,7)));
        proof.put("navigationClass",visible.getNavigation().getClass().getName());proof.put("attempts",navigationAttempts);proof.put("lastAttemptTick",lastNavigationAttempt);proof.put("accepted",navigationAccepted);
        proof.put("pathPresent",path!=null);if(path!=null){proof.put("pathNodes",path.getNodeCount());proof.put("pathCanReach",path.canReach());proof.put("pathDone",path.isDone());proof.put("pathNextNode",path.getNextNodeIndex());}
        navigationProof=Map.copyOf(proof);
    }
    private static void camera(ServerPlayer player,boolean spectator){
        player.setGameMode(spectator?GameType.SPECTATOR:GameType.CREATIVE);player.setNoGravity(true);player.setDeltaMovement(0,0,0);
        if(!player.teleportTo(player.level(),CAMERA_X,CAMERA_Y,CAMERA_Z,Set.of(),CAMERA_YAW,CAMERA_PITCH,true))throw new IllegalStateException("Cannot place model fixture camera");
    }
    private void requireOwner(IntegratedServer server){if(!server.isSameThread())throw new IllegalStateException("Model fixture requires the integrated-server owner");lastServerThread=Thread.currentThread().getName();}
    private static ServerPlayer player(IntegratedServer server,UUID id){var player=server.getPlayerList().getPlayer(id);if(player==null)throw new IllegalStateException("Model fixture player is absent");return player;}
}
