package dev.overhaul.shader.graph;

import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackRenderTargets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-runtime ping-pong state. A proposed pass changes logical bindings only after accepted submission. */
public final class PackTargetSwaps {
    public record Target(int color,int side){
        public Target{if(color<0||color>15||side<0||side>1)throw new IllegalArgumentException("Invalid color target pair");}
    }
    public record Routing(List<Target> outputs,Map<String,Target> samplers,boolean finalOutput){
        public Routing{outputs=java.util.Collections.unmodifiableList(new ArrayList<>(outputs));samplers=Map.copyOf(samplers);}
    }
    private final int[] current=new int[16];
    public Target current(int index){return new Target(index,current[index]);}
    public Routing route(CompiledShaderPack.Program program){
        boolean finalOutput=program.stage()==CompiledShaderPack.Stage.FINAL;
        List<Target> outputs=new ArrayList<>();Map<String,Target> samplers=new LinkedHashMap<>();
        if(!finalOutput)for(int color:program.code().framebuffer().colorTargets())outputs.add(color<0?null:new Target(color,current[color]^(program.scene()?0:1)));
        for(String name:program.code().descriptors().samplers().keySet()){
            int index=PackRenderTargets.colorIndex(name);if(index>=0)samplers.put(name,current(index));
        }
        if(program.scene())for(Target sampled:samplers.values())if(outputs.contains(sampled))
            throw new IllegalArgumentException("Scene shader reads its active color attachment: "+program.name());
        return new Routing(outputs,samplers,finalOutput);
    }
    public void complete(CompiledShaderPack.Program program){
        if(program.scene()||program.stage()==CompiledShaderPack.Stage.FINAL)return;
        boolean[] flip=new boolean[16];
        for(int color:program.code().framebuffer().colorTargets())if(color>=0)flip[color]=program.state().flips().getOrDefault("colortex"+color,true);
        for(var entry:program.state().flips().entrySet())if(entry.getValue())flip[PackRenderTargets.colorIndex(entry.getKey())]=true;
        for(int index=0;index<16;index++)if(flip[index])current[index]^=1;
    }
    public int[] snapshot(){return current.clone();}
    public void restore(int[] snapshot){if(snapshot.length!=16)throw new IllegalArgumentException("Invalid target swap snapshot");System.arraycopy(snapshot,0,current,0,16);}
}
