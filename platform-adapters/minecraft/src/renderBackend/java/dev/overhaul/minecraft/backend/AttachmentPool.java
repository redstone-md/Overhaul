package dev.overhaul.minecraft.backend;

import dev.overhaul.vulkan.FrameRing;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exclusive complete attachment bundles; only retired leases may enter the bounded idle cache. */
final class AttachmentPool implements AutoCloseable {
    private final OverhaulDeviceBackend owner;
    private final int capacity;
    private final ArrayDeque<AttachmentBundle> idle=new ArrayDeque<>();
    private final Set<Lease> active=Collections.newSetFromMap(new IdentityHashMap<>());
    private long renderPassCreations,framebufferCreations,privateViewCreations,reuses,evictions;
    private boolean closed;

    AttachmentPool(OverhaulDeviceBackend owner,int capacity) {
        if(capacity<0)throw new IllegalArgumentException("Negative attachment cache capacity");
        this.owner=owner; this.capacity=capacity;
    }
    Lease acquire(AttachmentKey key,List<? extends BackendResource> sources) {
        owner.requireOwner();
        if(closed)throw new IllegalStateException("Attachment pool is closed");
        AttachmentBundle selected=null;
        for(var candidate:idle)if(candidate.key().equals(key)){selected=candidate;break;}
        if(selected!=null){idle.remove(selected);reuses++;}
        else selected=new AttachmentBundle(this,owner,key,sources);
        Lease lease=new Lease(selected);active.add(lease);return lease;
    }
    /** Logical close evicts idle pins immediately; in-flight owners keep their own safe lease. */
    void invalidate(BackendResource resource) {
        owner.requireOwner();
        if(closed)return;
        var discarded=new ArrayList<AttachmentBundle>();
        idle.removeIf(bundle->{if(!bundle.references(resource))return false;discarded.add(bundle);return true;});
        for(var lease:active)if(lease.bundle.references(resource))lease.cacheable=false;
        for(var bundle:discarded){evictions++;bundle.close();}
    }
    Map<String,Long> statistics() {
        owner.requireOwner();
        return Map.of("attachmentRenderPassCreations",renderPassCreations,
                "attachmentFramebufferCreations",framebufferCreations,"attachmentPrivateViewCreations",privateViewCreations,
                "attachmentReuses",reuses,"attachmentEvictions",evictions,
                "attachmentIdle",(long)idle.size(),"attachmentActive",(long)active.size());
    }
    void renderPassCreated(){renderPassCreations++;}
    void framebufferCreated(){framebufferCreations++;}
    void privateViewCreated(){privateViewCreations++;}
    @Override public void close() {
        owner.requireOwner();if(closed)return;
        if(!active.isEmpty())throw new IllegalStateException("Retire attachment leases before closing their pool");
        closed=true;
        while(!idle.isEmpty())idle.removeFirst().close();
    }

    final class Lease implements AutoCloseable {
        private final AttachmentBundle bundle;
        private FrameRing.Submission completion;
        private boolean released,cacheable;
        private Lease(AttachmentBundle bundle){this.bundle=bundle;cacheable=bundle.sourcesOpen();}
        void create() {
            requireActive();
            bundle.create();
        }
        long renderPass(){requireActive();return bundle.renderPass();}
        long framebuffer(){requireActive();return bundle.framebuffer();}
        void retainUntil(FrameRing.Submission submitted) {
            requireActive();
            if(completion!=null)throw new IllegalStateException("Attachment lease already submitted");
            bundle.retainUntil(submitted);completion=submitted;
        }
        private void requireActive(){owner.requireOwner();if(released)throw new IllegalStateException("Attachment lease is released");}
        @Override public void close() {
            owner.requireOwner();if(released)return;
            // Normal queue retirement has already completed the fence. Explicit early close must also be safe.
            if(completion!=null)completion.await();
            // Waiting may retire this same lease through its owning batch's close callback.
            if(released)return;
            released=true;active.remove(this);
            if(closed||!cacheable||!bundle.sourcesOpen()||capacity==0){bundle.close();return;}
            while(idle.size()>=capacity){evictions++;idle.removeFirst().close();}
            idle.addLast(bundle);
        }
    }
}
