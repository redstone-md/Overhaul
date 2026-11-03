package dev.overhaul.minecraft.backend;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class ReadFootprintsTest {
    @Test void mergingPreservesRealGapsHalfOpenBoundariesAndResourceIdentity(){
        ReadFootprints<String> reads=new ReadFootprints<>();String first=new String("buffer"),second=new String("buffer");
        reads.add(first,16,16);reads.add(first,64,16);reads.add(first,20,4);
        assertFalse(reads.overlaps(first,32,32));assertFalse(reads.overlaps(first,80,16));
        assertTrue(reads.overlaps(first,31,2));assertFalse(reads.overlaps(second,16,16));
        reads.add(first,32,32);assertTrue(reads.overlaps(first,48,1));
        assertFalse(reads.overlaps(first,16,0));reads.clear();assertFalse(reads.overlaps(first,16,1));
        assertThrows(IllegalArgumentException.class,()->reads.add(first,-1,4));
        assertThrows(IllegalArgumentException.class,()->reads.overlaps(first,Long.MAX_VALUE,2));
    }
    @Test void randomizedMergesAndQueriesMatchAnIndependentByteCoverageOracle(){
        ReadFootprints<Object> reads=new ReadFootprints<>();Object[] buffers={new Object(),new Object()};boolean[][] expected=new boolean[2][256];Random random=new Random(704);
        for(int insertion=0;insertion<400;insertion++){
            int buffer=random.nextInt(2),offset=random.nextInt(256),length=random.nextInt(257-offset);
            reads.add(buffers[buffer],offset,length);for(int index=offset;index<offset+length;index++)expected[buffer][index]=true;
            for(int query=0;query<40;query++){
                int selected=random.nextInt(2),start=random.nextInt(256),count=random.nextInt(257-start);boolean overlap=false;
                for(int index=start;index<start+count;index++)overlap|=expected[selected][index];
                assertEquals(overlap,reads.overlaps(buffers[selected],start,count));
            }
        }
    }
}
