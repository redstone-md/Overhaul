package dev.overhaul.minecraft.backend;

import java.util.LinkedHashMap;
import java.util.Map;

/** Counts native commands belonging to accepted submissions, never capture attempts or GPU estimates. */
final class BackendDrawStatistics {
    private enum Metric {
        PASSES("acceptedPasses"),
        INDEXED("directIndexedCalls"),DIRECT("directNonIndexedCalls"),
        INDEXED_INDIRECT("indexedIndirectCalls"),INDEXED_COMMANDS("indexedIndirectCommands"),
        INDEXED_MULTI("indexedIndirectMultiRecordCalls"),INDEXED_INSTANCED("instancedIndexedIndirectCalls"),
        INDEXED_MAX("maxIndexedIndirectBatch",true),
        INDIRECT("nonIndexedIndirectCalls"),COMMANDS("nonIndexedIndirectCommands"),
        MULTI("nonIndexedIndirectMultiRecordCalls"),INSTANCED("instancedNonIndexedIndirectCalls"),
        MAX("maxNonIndexedIndirectBatch",true);
        final String name;final boolean maximum;
        Metric(String name){this(name,false);}
        Metric(String name,boolean maximum){this.name=name;this.maximum=maximum;}
    }
    private static final Metric[] METRICS=Metric.values();
    private final long[] totals=new long[METRICS.length];
    void accept(Recording recording){
        if(recording.accepted)throw new IllegalStateException("Draw recording already accepted");
        for(var metric:METRICS){
            int index=metric.ordinal();long value=recording.values[index];
            totals[index]=metric.maximum?Math.max(totals[index],value):Math.addExact(totals[index],value);
        }
        totals[Metric.PASSES.ordinal()]++;recording.accepted=true;
    }
    Map<String,Long> snapshot(){
        var result=new LinkedHashMap<String,Long>();
        for(var metric:METRICS)result.put(metric.name,totals[metric.ordinal()]);
        return Map.copyOf(result);
    }
    static final class Recording {
        private final long[] values=new long[METRICS.length];
        private boolean accepted;
        void direct(boolean indexed){add(indexed?Metric.INDEXED:Metric.DIRECT,1);}
        void indirect(boolean indexed,int count,boolean instanced){
            add(indexed?Metric.INDEXED_INDIRECT:Metric.INDIRECT,1);
            add(indexed?Metric.INDEXED_COMMANDS:Metric.COMMANDS,count);
            if(count>1)add(indexed?Metric.INDEXED_MULTI:Metric.MULTI,1);
            if(instanced)add(indexed?Metric.INDEXED_INSTANCED:Metric.INSTANCED,1);
            int maximum=(indexed?Metric.INDEXED_MAX:Metric.MAX).ordinal();
            values[maximum]=Math.max(values[maximum],count);
        }
        private void add(Metric metric,long value){
            if(accepted)throw new IllegalStateException("Accepted recording is immutable");
            values[metric.ordinal()]=Math.addExact(values[metric.ordinal()],value);
        }
    }
}
