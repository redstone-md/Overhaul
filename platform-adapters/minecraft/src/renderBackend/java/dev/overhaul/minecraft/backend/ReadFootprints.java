package dev.overhaul.minecraft.backend;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Owner-thread half-open read intervals; adjacent spans merge without filling real gaps. */
final class ReadFootprints<K> {
    private static final class Span {long start,end;Span(long start,long end){this.start=start;this.end=end;}}
    private final Map<K,List<Span>> resources=new IdentityHashMap<>();

    void add(K resource,long offset,long length){
        long end=end(offset,length);if(length==0)return;
        List<Span> spans=resources.computeIfAbsent(resource,ignored->new ArrayList<>());
        int first=firstEndingAfter(spans,offset,true);
        if(first==spans.size()||spans.get(first).start>end){spans.add(first,new Span(offset,end));return;}
        Span merged=spans.get(first);
        if(merged.start<=offset&&merged.end>=end)return;
        merged.start=Math.min(merged.start,offset);merged.end=Math.max(merged.end,end);
        int stop=first+1;
        while(stop<spans.size()&&spans.get(stop).start<=merged.end){merged.end=Math.max(merged.end,spans.get(stop).end);stop++;}
        if(stop>first+1)spans.subList(first+1,stop).clear();
    }
    boolean overlaps(K resource,long offset,long length){
        long end=end(offset,length);if(length==0)return false;
        List<Span> spans=resources.get(resource);if(spans==null)return false;
        int index=firstEndingAfter(spans,offset,false);
        return index<spans.size()&&spans.get(index).start<end;
    }
    private static int firstEndingAfter(List<Span> spans,long offset,boolean includeTouching){
        int low=0,high=spans.size();
        while(low<high){int middle=(low+high)>>>1;long end=spans.get(middle).end;
            if(end<offset||(!includeTouching&&end==offset))low=middle+1;else high=middle;
        }return low;
    }
    private static long end(long offset,long length){
        if(offset<0||length<0||length>Long.MAX_VALUE-offset)throw new IllegalArgumentException("Invalid read interval");
        return offset+length;
    }
    void clear(){resources.clear();}
}
