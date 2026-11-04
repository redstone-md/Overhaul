package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.FrameRing;
import dev.overhaul.vulkan.VulkanContext;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;

/** One ordered graphics lane. Operations and their snapshots belong to the accepted fence. */
final class BlazeQueue implements AutoCloseable {
    interface Operation extends AutoCloseable {
        void record(VkCommandBuffer command);
        default void submitted(FrameRing.Submission submission) {}
        @Override void close();
    }
    private static final int MAX_OPERATIONS=512;
    private static final long MAX_STAGING=64L*1024*1024;
    private static final int MAX_RETIRED_RESOURCES=4096;
    private final FrameRing frames;
    private final VulkanContext context;
    private Batch pending=new Batch();
    private FrameRing.Submission latest;
    private long stagingBytes;
    private boolean closed;
    private Throwable failedSubmission;
    private int retiredResources;

    BlazeQueue(VulkanContext context){this.context=context;frames=new FrameRing(context,3);}
    void add(Operation operation,long bytes){
        try{
            requireUsable();
            if(bytes<0||bytes>MAX_STAGING)throw new IllegalArgumentException("Staging operation exceeds the bounded batch size");
            if(pending.operations.size()>=MAX_OPERATIONS||stagingBytes+bytes>MAX_STAGING)flush();
            long nextBytes=Math.addExact(stagingBytes,bytes);
            pending.operations.add(operation);stagingBytes=nextBytes;
        }catch(RuntimeException|Error failure){try{operation.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    /** The newest operation not yet submitted; recording it later still keeps its place in order. */
    Operation last(){return pending.operations.isEmpty()?null:pending.operations.get(pending.operations.size()-1);}
    /** Removed native objects cannot be resolved by later draws; existing snapshots retire first. */
    void retire(AutoCloseable resource){
        if(resource==null)return;
        context.device();
        if(retiredResources>=MAX_RETIRED_RESOURCES)reclaim();
        retiredResources++;
        AutoCloseable tracked=new Retirement(resource);
        if(!pending.operations.isEmpty())pending.removed.add(tracked);
        else if(latest!=null)latest.retireAfterFence(tracked);
        else closeResource(tracked);
    }
    FrameRing.Submission flush(){
        requireUsable();
        frames.poll();
        if(pending.operations.isEmpty())return latest;
        long start=BlazeStats.start();
        Batch batch=pending;pending=new Batch();stagingBytes=0;
        FrameRing.Submission receipt=null;
        try{
            receipt=frames.submit(frame->{
                frame.retireAfterFence(batch);
                BlazePass.recordAll(frame.command(),batch.operations);
            });
            latest=receipt;
            batch.accepted=true;
            // No fence polling is allowed between acceptance and committing all layout proposals.
            Throwable acceptanceFailure=null;
            for(Operation operation:batch.operations){
                try{operation.submitted(receipt);}catch(RuntimeException|Error error){acceptanceFailure=Batch.collect(acceptanceFailure,error);}
            }
            if(acceptanceFailure instanceof RuntimeException error)throw error;if(acceptanceFailure instanceof Error error)throw error;
            BlazeStats.flush(start);
            return receipt;
        }catch(RuntimeException|Error failure){
            failedSubmission=failure;
            // Acceptance callbacks normally cannot fail. If one does, storage still belongs to the GPU.
            if(batch.accepted){
                try{receipt.await();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
                throw failure;
            }
            // FrameRing drains the native queue on recording/submission failure before retiring batch.
            try{batch.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    void drain(){if(failedSubmission==null)flush();frames.drain();}
    void reclaim(){drain();}
    long blockingWaitCalls(){return frames.blockingWaitCalls();}
    private void requireUsable(){
        context.device();
        if(closed)throw new IllegalStateException("Blaze submission lane is closed");
        if(failedSubmission!=null)throw new IllegalStateException("Blaze submission lane failed",failedSubmission);
    }
    @Override public void close(){
        if(closed)return;
        try{drain();}finally{closed=true;frames.close();}
    }
    static void closeResource(AutoCloseable resource){
        try{resource.close();}catch(RuntimeException|Error failure){throw failure;}
        catch(Exception failure){throw new IllegalStateException("Blaze native resource release failed",failure);}
    }
    private final class Retirement implements AutoCloseable {
        private AutoCloseable resource;
        private Retirement(AutoCloseable resource){this.resource=resource;}
        @Override public void close(){
            if(resource==null)return;AutoCloseable old=resource;resource=null;
            try{closeResource(old);}finally{retiredResources--;}
        }
    }
    private static final class Batch implements AutoCloseable {
        private final List<Operation> operations=new ArrayList<>();
        private final List<AutoCloseable> removed=new ArrayList<>();
        private boolean closed;
        private boolean accepted;
        @Override public void close(){
            if(closed)return;closed=true;Throwable failure=null;
            // Rollback proposed image layouts in the reverse order in which they were recorded.
            for(int index=operations.size()-1;index>=0;index--){
                try{operations.get(index).close();}catch(RuntimeException|Error error){failure=collect(failure,error);}
            }
            for(AutoCloseable resource:removed){
                try{closeResource(resource);}catch(RuntimeException|Error error){failure=collect(failure,error);}
            }
            operations.clear();removed.clear();
            if(failure instanceof RuntimeException error)throw error;if(failure instanceof Error error)throw error;
        }
        private static Throwable collect(Throwable failure,Throwable error){if(failure==null)return error;failure.addSuppressed(error);return failure;}
    }
}
