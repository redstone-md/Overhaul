package dev.overhaul.core;

import dev.overhaul.core.memory.DirectSlabPool;
import dev.overhaul.core.mesh.ChunkMesh;
import dev.overhaul.core.mesh.ChunkMesher;
import dev.overhaul.core.world.BlockLighting;
import dev.overhaul.core.world.ChunkPipeline;
import dev.overhaul.core.world.ChunkSnapshot;
import dev.overhaul.core.world.ChunkPacket;
import dev.overhaul.core.world.MeshMailbox;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkPipelineTest {
    @Test void paletteRoundTripsAcrossLongBoundariesAndDoesNotAliasBuilder() {
        ChunkSnapshot.Builder builder = ChunkSnapshot.builder(8);
        for (int y = -1; y <= 8; y++) for (int z = -1; z <= 8; z++) for (int x = -1; x <= 8; x++) {
            builder.set(x, y, z, Math.floorMod(x + y * 10 + z * 100, 19));
        }
        ChunkSnapshot snapshot = builder.build();
        builder.fill(22);
        for (int y = -1; y <= 8; y++) for (int z = -1; z <= 8; z++) for (int x = -1; x <= 8; x++) {
            assertEquals(Math.floorMod(x + y * 10 + z * 100, 19), snapshot.state(x, y, z));
        }
        assertEquals(19, snapshot.paletteSize());
        assertTrue(snapshot.packedStateBytes() < 10 * 10 * 10 * 4);
    }

    @Test void haloCullsBoundaryFacesAndMeshOwnsNativeStorage() {
        DirectSlabPool pool = new DirectSlabPool(65536, 2);
        ChunkSnapshot snapshot = ChunkSnapshot.builder(2).set(0, 0, 0, 7).set(-1, 0, 0, 7).build();
        ChunkMesh mesh = new ChunkMesher().mesh(snapshot, pool);
        assertEquals(30, mesh.indexCount());
        assertEquals(20, mesh.vertexCount());
        assertTrue(mesh.vertexBuffer().isDirect());
        assertTrue(mesh.vertexBuffer().isReadOnly());
        ByteBuffer indices = mesh.indexBuffer();
        for (int i = 0; i < mesh.indexCount(); i++) assertTrue(indices.getInt(i * 4) < mesh.vertexCount());
        mesh.close();
        assertEquals(0, pool.activeLeases());
        assertThrows(IllegalStateException.class, mesh::vertexBuffer);
        pool.close();
    }

    @Test void allFacesHaveOutwardWinding() {
        DirectSlabPool pool = new DirectSlabPool(65536, 1);
        try (ChunkMesh mesh = new ChunkMesher().mesh(ChunkSnapshot.builder(1).set(0, 0, 0, 1).build(), pool)) {
            ByteBuffer vertices = mesh.vertexBuffer();
            int[][] normal = {{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
            for (int face = 0; face < 6; face++) {
                float[] a = position(vertices, face * 4), b = position(vertices, face * 4 + 1), c = position(vertices, face * 4 + 2);
                float ux = b[0]-a[0], uy = b[1]-a[1], uz = b[2]-a[2];
                float vx = c[0]-a[0], vy = c[1]-a[1], vz = c[2]-a[2];
                float dot = (uy*vz-uz*vy)*normal[face][0] + (uz*vx-ux*vz)*normal[face][1] + (ux*vy-uy*vx)*normal[face][2];
                assertTrue(dot > 0, "face " + face);
            }
        }
        pool.close();
    }

    @Test void lightPropagatesAroundObstaclesAndFromHalo() {
        ChunkSnapshot snapshot = ChunkSnapshot.builder(4).set(1,1,1,1).emission(1,1,1,15)
                .set(2,1,1,2).emission(-1,0,0,12).build();
        BlockLighting.LightVolume light = new BlockLighting().calculate(snapshot);
        assertEquals(15, light.at(1,1,1));
        assertEquals(14, light.at(1,2,1));
        assertEquals(0, light.at(2,1,1));
        assertEquals(11, light.at(3,1,1));
        assertTrue(light.at(0,0,0) >= 11);
    }

    @Test void asyncPipelineAndRevisionMailboxReleaseObsoleteMeshes() throws Exception {
        DirectSlabPool pool = new DirectSlabPool(65536, 4);
        try (ChunkPipeline pipeline = new ChunkPipeline(2, 8, pool); MeshMailbox mailbox = new MeshMailbox()) {
            ChunkMesh old = pipeline.submit(ChunkSnapshot.builder(2).revision(1).set(0,0,0,1).build()).get(5, TimeUnit.SECONDS);
            ChunkMesh current = pipeline.submit(ChunkSnapshot.builder(2).revision(2).set(0,0,0,1).build()).get(5, TimeUnit.SECONDS);
            mailbox.expect(2);
            mailbox.publish(old);
            mailbox.publish(current);
            assertEquals(1, pool.activeLeases());
            try (ChunkMesh delivered = mailbox.poll()) { assertEquals(2, delivered.revision()); assertEquals(36, delivered.indexCount()); }
            assertNull(mailbox.poll());
        }
        assertEquals(0, pool.activeLeases());
        pool.close();
    }

    @Test void packetCodecRoundTripsAndRejectsMalformedCountsBeforeAllocation() throws Exception {
        DirectSlabPool pool = new DirectSlabPool(65536, 4);
        ChunkSnapshot snapshot = ChunkSnapshot.builder(4).revision(99).set(2,1,3,45).emission(2,1,3,8).build();
        try (ChunkPacket packet = ChunkPacket.encode(snapshot, pool)) {
            assertTrue(packet.bytes().isDirect());
            assertTrue(packet.bytes().isReadOnly());
            ChunkSnapshot decoded = packet.decode();
            assertEquals(99, decoded.revision());
            assertEquals(45, decoded.state(2,1,3));
            assertEquals(8, decoded.emission(2,1,3));
            ByteBuffer malformed = ByteBuffer.allocate(snapshot.encodedBytes());
            malformed.put(packet.bytes()).flip();
            malformed.putInt(16, Integer.MAX_VALUE);
            assertThrows(IllegalArgumentException.class, () -> ChunkSnapshot.decode(malformed));
            assertThrows(IllegalArgumentException.class, () -> ChunkSnapshot.decode(ByteBuffer.allocate(4)));
        }
        try (ChunkPipeline pipeline = new ChunkPipeline(1, 8, pool)) {
            ChunkPacket packet = ChunkPacket.encode(snapshot, pool);
            try (ChunkMesh mesh = pipeline.submitPacket(packet).get(5, TimeUnit.SECONDS)) {
                assertEquals(99, mesh.revision());
            }
        }
        assertEquals(0, pool.activeLeases());
        pool.close();
    }

    private static float[] position(ByteBuffer buffer, int vertex) {
        int offset = vertex * ChunkMesh.VERTEX_STRIDE;
        return new float[] {buffer.getFloat(offset),buffer.getFloat(offset+4),buffer.getFloat(offset+8)};
    }
}
