package dev.overhaul.minecraft.smoke;

import dev.overhaul.adapter.ClientRuntime;
import dev.overhaul.adapter.SectionKey;
import dev.overhaul.minecraft.ClientSmokeSession;
import dev.overhaul.minecraft.MinecraftSectionSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Development-only real-world integration driver; never compiled into normal adapter jars. */
public final class WorldSmokeController implements ClientSmokeSession {
    private enum Phase { WAIT_LOAD, CREATING, WAIT_WORLD, WAIT_FIXTURE, WAIT_MESH, WAIT_PROBE, DISCONNECTING, DONE }
    private static final long SEED=424242L;
    private static final Logger LOG=Logger.getLogger("overhaul");
    private final Minecraft minecraft;
    private final ClientRuntime runtime;
    private final MinecraftSectionSource source;
    private final WorldSmokeProbe probe;
    private final WorldSmokeFixture fixture=new WorldSmokeFixture();
    private final Path runDirectory,receipt;
    private final String worldId="overhaul-smoke-"+UUID.randomUUID();
    private final long started=System.nanoTime();
    private Phase phase=Phase.WAIT_LOAD;
    private SectionKey section;
    private CompletableFuture<String> additional;
    private CompletableFuture<Void> fixturePlacement;
    private String evidence="not configured",failure;
    private long expectedRevision,initialAccepted;
    private int indices;

    public WorldSmokeController(Minecraft minecraft,ClientRuntime runtime,MinecraftSectionSource source,WorldSmokeProbe probe) {
        this.minecraft=minecraft; this.runtime=runtime; this.source=source; this.probe=probe;
        try {
            runDirectory=minecraft.gameDirectory.toPath().toRealPath();
            Path expected=Path.of(System.getProperty("overhaul.diagnostics.expectedRunDirectory")).toRealPath();
            if (!runDirectory.equals(expected) || !expected.getFileName().toString().equals("fabric-26.3")) {
                throw new IllegalStateException("Smoke must run in its explicit isolated development directory");
            }
            if (!minecraft.getLevelSource().getBaseDir().toAbsolutePath().normalize().equals(runDirectory.resolve("saves"))) {
                throw new IllegalStateException("Smoke world source is outside its isolated saves directory");
            }
            receipt=runDirectory.resolve("overhaul-world-smoke-latest.json");
            Files.deleteIfExists(receipt);
        } catch (IOException error) { throw new IllegalStateException("Cannot initialize world smoke receipt",error); }
    }

    @Override public void tick() {
        if (phase==Phase.DONE || phase==Phase.CREATING) return;
        try {
            if (System.nanoTime()-started>TimeUnit.SECONDS.toNanos(180)) throw new IllegalStateException("World smoke timed out in "+phase);
            Throwable workerFailure=runtime.takeFailure();
            if (workerFailure!=null) throw new IllegalStateException("Live snapshot worker failed",workerFailure);
            switch (phase) {
                case WAIT_LOAD: if (minecraft.isGameLoadFinished()) createWorld(); break;
                case WAIT_WORLD: captureWhenLoaded(); break;
                case WAIT_FIXTURE: captureAfterClientFixtureUpdate(); break;
                case WAIT_MESH: acceptMesh(); break;
                case WAIT_PROBE: if (additional.isDone()) { evidence=additional.join(); disconnect(); } break;
                case DISCONNECTING: finishWhenDisconnected(); break;
                default: break;
            }
        } catch (RuntimeException error) {
            failure=error.toString();
            LOG.log(java.util.logging.Level.SEVERE,"Overhaul world smoke failed",error);
            runtime.leaveWorld();
            if (minecraft.level!=null || minecraft.hasSingleplayerServer()) minecraft.disconnectWithSavingScreen();
            writeReceipt("FAIL"); phase=Phase.DONE; minecraft.stop();
        }
    }

    private void createWorld() {
        if (minecraft.level!=null || minecraft.hasSingleplayerServer()) throw new IllegalStateException("Refusing to replace an existing loaded world");
        if (minecraft.getLevelSource().levelExists(worldId)) throw new IllegalStateException("Disposable world name already exists");
        minecraft.options.pauseOnLostFocus=false;
        minecraft.options.renderDistance().set(2);
        minecraft.options.simulationDistance().set(5);
        phase=Phase.CREATING;
        LevelSettings settings=new LevelSettings(worldId,GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL,false,true),false,WorldDataConfiguration.DEFAULT);
        LOG.info("Overhaul world smoke creating "+worldId+" seed="+SEED+" preset=flat_all_dimensions");
        minecraft.createWorldOpenFlows().createFreshLevel(worldId,settings,new WorldOptions(SEED,false,false),
                WorldPresets::createTestWorldDimensions,new TitleScreen());
        phase=Phase.WAIT_WORLD;
    }

    private void captureWhenLoaded() {
        if (minecraft.level==null || minecraft.player==null || !minecraft.hasSingleplayerServer()) return;
        section=new SectionKey(0,0,0);
        if (!source.haloLoaded(section)) return;
        fixturePlacement=fixture.place(minecraft.getSingleplayerServer());
        phase=Phase.WAIT_FIXTURE;
    }

    private void captureAfterClientFixtureUpdate() {
        if (!fixturePlacement.isDone()) return;
        fixturePlacement.join();
        if (minecraft.level==null || !fixture.clientMatches(minecraft.level) || !source.haloLoaded(section)) return;
        LOG.info("Overhaul world smoke verified client packets for "+fixture.verifiedClientStates()+" actual block fixtures");
        initialAccepted=runtime.acceptedMeshes();
        runtime.capture(minecraft.level,section,source);
        long first=runtime.capturedRevision();
        // Supersede real captured data; both captures read this actual loaded client level.
        runtime.capture(minecraft.level,section,source);
        expectedRevision=runtime.capturedRevision();
        if (first<=0 || expectedRevision<=first) throw new IllegalStateException("Live capture revision did not advance");
        LOG.info("Overhaul world smoke captured live section="+section.x()+","+section.y()+","+section.z()
                +" revisions="+first+","+expectedRevision+" source="+minecraft.level.getClass().getName());
        phase=Phase.WAIT_MESH;
    }

    private void acceptMesh() {
        if (runtime.acceptedMeshes()<=initialAccepted) return;
        if (runtime.acceptedRevision()!=expectedRevision) throw new IllegalStateException("Accepted stale chunk revision");
        indices=runtime.acceptedIndices();
        if (indices<=0) throw new IllegalStateException("Actual loaded world produced empty geometry");
        LOG.info("Overhaul world smoke accepted revision="+expectedRevision+" indices="+indices);
        if (probe==null) disconnect();
        else { additional=probe.capture(minecraft,section); phase=Phase.WAIT_PROBE; }
    }

    private void disconnect() {
        close();
        runtime.leaveWorld();
        if (runtime.retiredLeases()!=0) throw new IllegalStateException("Native leases remain after world unload");
        phase=Phase.DISCONNECTING;
        minecraft.disconnectWithSavingScreen();
    }

    @Override public void close() { if (probe!=null) probe.close(); }

    private void finishWhenDisconnected() {
        if (minecraft.level!=null || minecraft.hasSingleplayerServer()) return;
        writeReceipt("PASS");
        LOG.info("Overhaul world smoke PASS world="+worldId+" revision="+expectedRevision+" indices="+indices
                +" retiredLeases="+runtime.retiredLeases()+" disconnected=true");
        phase=Phase.DONE; minecraft.stop();
    }

    private void writeReceipt(String status) {
        String json="{\n  \"status\":"+SmokeJson.quote(status)+",\n  \"world\":"+SmokeJson.quote(worldId)+",\n  \"seed\":"+SEED
                +",\n  \"revision\":"+expectedRevision+",\n  \"indices\":"+indices
                +",\n  \"retiredLeases\":"+runtime.retiredLeases()+",\n  \"additionalProbe\":"+SmokeJson.quote(evidence)
                +",\n  \"fixturePlacementThread\":"+SmokeJson.quote(fixture.placementThread())
                +",\n  \"clientFixtureStatesVerified\":"+fixture.verifiedClientStates()
                +",\n  \"fixtures\":"+fixture.toJson()
                +",\n  \"failure\":"+SmokeJson.quote(failure==null ? "" : failure)+"\n}\n";
        try { Files.writeString(receipt,json); }
        catch (IOException error) { throw new IllegalStateException("Cannot write smoke receipt",error); }
    }
}
