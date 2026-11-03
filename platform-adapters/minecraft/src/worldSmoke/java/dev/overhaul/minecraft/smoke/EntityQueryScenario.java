package dev.overhaul.minecraft.smoke;

import dev.overhaul.minecraft.world.SectionQueryAccess;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Continuation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.AABB;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** All fields and operations belong to one real server owner; reflection is confined to this dev-only proof. */
final class EntityQueryScenario implements AutoCloseable {
    private static final int ORDINARY_TICKS=40;
    private static final AABB AREA=new AABB(-12,2,-12,12,194,28);
    private final IntegratedServer server;
    private final ServerLevel level;
    private final PersistentEntitySectionManager<Entity> manager;
    private final EntitySectionStorage<Entity> storage;
    private final SectionQueryAccess access;
    private final LevelEntityGetter<Entity> getter;
    private final List<Entity> owned=new ArrayList<>();
    private final List<ArmorStand> stands=new ArrayList<>();
    private final List<Map<String,Object>> cases=new ArrayList<>();
    private final Map<String,Object> report=new LinkedHashMap<>();
    private Map<String,Long> initial,beforeOrdinary;
    private Pig pig;
    private long ordinaryStart;
    private int pigStartTicks;
    private boolean closed;

    @SuppressWarnings("unchecked") EntityQueryScenario(IntegratedServer server){
        this.server=server;requireOwner();level=server.overworld();
        manager=(PersistentEntitySectionManager<Entity>)read(level,ServerLevel.class,"entityManager");
        storage=(EntitySectionStorage<Entity>)read(manager,PersistentEntitySectionManager.class,"sectionStorage");
        if(!((Object)storage instanceof SectionQueryAccess actual))throw new IllegalStateException("Actual server entity storage query hook is absent");
        access=actual;getter=manager.getEntityGetter();
    }
    Map<String,Object> start(){
        requireOwner();
        try{
            initial=access.overhaul$queryStatistics();
            for(int z:new int[]{-8,8,24})for(int x:new int[]{-8,8}){
                require(level.hasChunk(Math.floorDiv(x,16),Math.floorDiv(z,16)),"Fixture refuses to load missing horizontal chunks");
                for(int part=0;part<12;part++){
                    double y=8+16*part;require(y>=level.getMinY()&&y+3<level.getMaxY(),"Fixture exceeds world height");
                    var stand=new ArmorStand(level,x,y,z);stand.setNoGravity(true);
                    require(level.addFreshEntity(stand),"Cannot register actual armor stand");owned.add(stand);stands.add(stand);
                }
            }
            pig=EntityTypes.PIG.create(level,EntitySpawnReason.COMMAND);
            require(pig!=null,"Cannot construct normal vanilla pig");pig.setPos(3,5,-3);pig.setNoGravity(true);
            require(level.addFreshEntity(pig),"Cannot register actual vanilla AI entity");owned.add(pig);
            report.put("world",server.getWorldData().getLevelName());
            report.put("serverThread",Thread.currentThread().getName());report.put("fixtureStands",stands.size());
            report.put("fixtureOccupiedSections",stands.stream().map(entity->SectionPos.asLong(entity.blockPosition())).distinct().count());
            baselineQueries();movement();removal();visibility();bypassException();
            beforeOrdinary=access.overhaul$queryStatistics();ordinaryStart=level.getGameTime();pigStartTicks=pig.tickCount;
            report.put("beforeOrdinaryTraffic",beforeOrdinary);
            return null;
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    Map<String,Object> advance(){
        requireOwner();
        try{
            if(level.getGameTime()-ordinaryStart<ORDINARY_TICKS||pig.tickCount-pigStartTicks<ORDINARY_TICKS)return null;
            var afterOrdinary=access.overhaul$queryStatistics();
            require(number(afterOrdinary,"queries")>number(beforeOrdinary,"queries"),"No ordinary server traffic used the optimized query path between proof operations");
            require(!pig.isRemoved()&&!pig.isNoAi()&&pig.tickCount-pigStartTicks>=ORDINARY_TICKS,"Vanilla AI entity did not tick normally");
            report.put("ordinaryWorldTicks",level.getGameTime()-ordinaryStart);report.put("pigTicks",pig.tickCount-pigStartTicks);
            report.put("afterOrdinaryTraffic",afterOrdinary);
            // Counter growth is ordinary traffic evidence; it is not attributed to a particular AI caller.
            report.put("trafficAttribution","Ordinary server ticks with a normal AI-enabled pig; no specific caller attribution");
            equalQuery("after-ordinary-untyped",()->untyped(AREA));equalQuery("after-ordinary-typed",()->typed(AREA));
            var beforeCleanup=access.overhaul$queryStatistics();close();
            for(Entity entity:owned)require(entity.isRemoved()&&getter.get(entity.getId())==null&&getter.get(entity.getUUID())==null,"Removed fixture remains in authoritative ID/UUID lookup");
            require(untyped(AREA).stream().noneMatch(id->owned.stream().anyMatch(entity->entity.getId()==id)),"Removed fixture remains in spatial lookup");
            var finished=access.overhaul$queryStatistics();
            require(number(finished,"queries")>number(initial,"queries"),"Optimized authoritative queries did not execute");
            require(number(finished,"fallbacks")>number(initial,"fallbacks"),"Large-query fallback did not execute");
            require(number(finished,"vanillaBypasses")>number(initial,"vanillaBypasses"),"Original oracle bypass did not execute");
            report.put("initialStatistics",initial);report.put("beforeCleanup",beforeCleanup);report.put("finalStatistics",finished);
            report.put("orderedOracleCases",List.copyOf(cases));report.put("removedFixtures",owned.size());report.put("cleanupComplete",true);
            return Map.copyOf(report);
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    private void baselineQueries(){
        var all=equalQuery("initial-untyped",()->untyped(AREA));
        var typed=equalQuery("initial-typed",()->typed(AREA));
        require(typed.size()==stands.size()&&all.containsAll(typed),"Actual standing fixtures are not all query-accessible");
        for(int index:new int[]{0,11,12,36,71}){
            AABB bounds=stands.get(index).getBoundingBox().inflate(.1);
            require(equalQuery("small-"+index,()->typed(bounds)).contains(stands.get(index).getId()),"Small real AABB missed its fixture");
        }
        equalQuery("empty",()->typed(new AABB(11,3,11,12,4,12)));
        equalQuery("large-original-fallback",()->typed(new AABB(-1000,level.getMinY(),-1000,1000,level.getMaxY(),1000)));
        AABB localColumn=new AABB(-9,7,-9,-7,43,-7);
        var localExpected=typed(localColumn);require(localExpected.size()==3,"Local column must contain exactly three fixture stands");
        var limited=equalQuery("typed-limit-three",()->{
            var found=new ArrayList<ArmorStand>();level.getEntities(EntityTypes.ARMOR_STAND,localColumn,entity->true,found,3);return ids(found);
        });
        require(limited.equals(localExpected),"Typed limit did not preserve ordered early abort");
        var aborted=equalQuery("getter-abort-first",()->{
            var found=new ArrayList<Integer>();getter.get(EntityTypes.ARMOR_STAND,localColumn,entity->{found.add(entity.getId());return Continuation.ABORT;});return List.copyOf(found);
        });
        require(aborted.equals(localExpected.subList(0,1)),"Getter abort visited an extra entity");
        long beforeNested=number(access.overhaul$queryStatistics(),"queries");
        equalQuery("reentrant",()->{
            var rows=new ArrayList<List<Integer>>();
            getter.get(EntityTypes.ARMOR_STAND,localColumn,entity->{
                rows.add(List.of(entity.getId()));rows.add(typed(entity.getBoundingBox().inflate(.1)));return rows.size()>=6?Continuation.ABORT:Continuation.CONTINUE;
            });return List.copyOf(rows);
        });
        require(number(access.overhaul$queryStatistics(),"queries")-beforeNested>=4,"Reentrant proof did not execute the optimized outer query and all three nested queries");
    }
    private void movement(){
        var moving=stands.getFirst();AABB old=moving.getBoundingBox().inflate(.1);int id=moving.getId();
        long previous=SectionPos.asLong(moving.blockPosition());moving.setPos(8,moving.getY(),-8);
        require(SectionPos.asLong(moving.blockPosition())!=previous,"Movement did not cross a section boundary");
        require(!equalQuery("movement-old-section",()->typed(old)).contains(id),"Moved entity remained in its previous AABB");
        require(equalQuery("movement-new-section",()->typed(moving.getBoundingBox().inflate(.1))).contains(id),"Moved entity missing from destination section");
        require(getter.get(id)==moving&&getter.get(moving.getUUID())==moving,"Movement changed authoritative ID/UUID identity");
    }
    private void removal(){
        var removed=stands.getLast();AABB old=removed.getBoundingBox().inflate(.1);removed.discard();
        require(!equalQuery("removal",()->typed(old)).contains(removed.getId()),"Discarded entity remained in spatial query");
        require(getter.get(removed.getId())==null&&getter.get(removed.getUUID())==null,"Discarded entity remained in lookup");
    }
    private void visibility(){
        var chunk=new ChunkPos(-1,-1);long node=SectionPos.asLong(-1,1,-1);
        var section=storage.getSection(node);require(section!=null,"Visibility fixture section is absent");
        Visibility previous=section.getStatus();AABB box=new AABB(-16,0,-16,0,194,0);
        try{
            manager.updateChunkStatus(chunk,Visibility.HIDDEN);
            require(equalQuery("visibility-hidden",()->typed(box)).isEmpty(),"Hidden sections remained query accessible");
            manager.updateChunkStatus(chunk,Visibility.TRACKED);
            require(!equalQuery("visibility-tracked",()->typed(box)).isEmpty(),"Tracked sections were excluded");
            manager.updateChunkStatus(chunk,Visibility.TICKING);
            require(!equalQuery("visibility-ticking",()->typed(box)).isEmpty(),"Ticking sections were excluded");
        }finally{manager.updateChunkStatus(chunk,previous);}
        require(storage.getSection(node).getStatus()==previous,"Chunk visibility was not restored");
        report.put("restoredVisibility",previous.toString());
    }
    private void bypassException(){
        RuntimeException sentinel=new IllegalStateException("entity query scoped oracle sentinel");
        try{access.overhaul$withVanilla(()->access.overhaul$withVanilla(()->{throw sentinel;}));throw new AssertionError("Oracle swallowed failure");}
        catch(RuntimeException actual){require(actual==sentinel,"Oracle changed the exception identity");}
        long before=number(access.overhaul$queryStatistics(),"queries");
        AABB small=stands.get(11).getBoundingBox().inflate(.1);
        equalQuery("after-nested-oracle-exception",()->typed(small));
        require(number(access.overhaul$queryStatistics(),"queries")>before,"Original oracle scope leaked after exception");
    }
    private <T>T equalQuery(String name,Supplier<T> query){
        requireOwner();T optimized=query.get();var original=new AtomicReference<T>();
        access.overhaul$withVanilla(()->original.set(query.get()));
        require(optimized.equals(original.get()),"Ordered vanilla oracle mismatch: "+name+" optimized="+optimized+" vanilla="+original.get());
        cases.add(Map.of("case",name,"orderedIds",optimized));return optimized;
    }
    private List<Integer> typed(AABB box){return ids(level.getEntities(EntityTypes.ARMOR_STAND,box,entity->true));}
    private List<Integer> untyped(AABB box){return ids(level.getEntities((Entity)null,box,entity->true));}
    private static List<Integer> ids(List<? extends Entity> values){return values.stream().map(Entity::getId).toList();}
    private static Object read(Object target,Class<?> declared,String name){
        try{Field field=declared.getDeclaredField(name);field.setAccessible(true);return field.get(target);}
        catch(ReflectiveOperationException failure){throw new IllegalStateException("Pinned entity manager field is unavailable: "+declared.getName()+"."+name,failure);}
    }
    private static long number(Map<String,Long> values,String name){Long value=values.get(name);if(value==null)throw new IllegalStateException("Missing query counter "+name);return value;}
    private void requireOwner(){if(!server.isSameThread())throw new IllegalStateException("Entity queries and fixture mutations require the integrated-server owner");}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    @Override public void close(){requireOwner();if(closed)return;closed=true;for(Entity entity:owned)if(!entity.isRemoved())entity.discard();}
}
