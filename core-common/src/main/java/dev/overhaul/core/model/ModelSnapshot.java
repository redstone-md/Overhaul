package dev.overhaul.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Game-thread capture; positions stay local to a double-precision world origin. */
public final class ModelSnapshot {
    private final long revision;
    private final double originX,originY,originZ;
    private final List<ModelQuad> quads;

    public ModelSnapshot(long revision,double originX,double originY,double originZ,List<ModelQuad> quads) {
        if (!Double.isFinite(originX)||!Double.isFinite(originY)||!Double.isFinite(originZ)) throw new IllegalArgumentException("Nonfinite model origin");
        List<ModelQuad> copy=new ArrayList<ModelQuad>(quads);
        for (ModelQuad quad:copy) if (quad==null) throw new NullPointerException("model quad");
        this.revision=revision; this.originX=originX; this.originY=originY; this.originZ=originZ;
        this.quads=Collections.unmodifiableList(copy);
    }
    public long revision(){return revision;}
    public double originX(){return originX;} public double originY(){return originY;} public double originZ(){return originZ;}
    public List<ModelQuad> quads(){return quads;}
}
