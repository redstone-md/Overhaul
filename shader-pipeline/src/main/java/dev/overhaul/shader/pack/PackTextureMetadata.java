package dev.overhaul.shader.pack;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

/** Reads only texture sampling policy; unrelated valid metadata is bounded and skipped. */
record PackTextureMetadata(boolean blur,boolean clamp) {
    static PackTextureMetadata read(String source)throws IOException{
        boolean blur=false,clamp=false;
        try(JsonReader reader=new JsonReader(new StringReader(source))){
            reader.setLenient(false);reader.beginObject();Set<String> rootNames=new HashSet<>();
            while(reader.hasNext()){
                String name=reader.nextName();if(!rootNames.add(name))throw new IOException("Duplicate texture metadata member: "+name);
                if(!name.equals("texture")){skip(reader,1);continue;}
                reader.beginObject();Set<String> names=new HashSet<>();
                while(reader.hasNext()){
                    name=reader.nextName();if(!names.add(name))throw new IOException("Duplicate texture sampling member: "+name);
                    if(name.equals("blur")||name.equals("clamp")){
                        if(reader.peek()!=JsonToken.BOOLEAN)throw new IOException("Texture sampling metadata requires boolean: "+name);
                        boolean value=reader.nextBoolean();if(name.equals("blur"))blur=value;else clamp=value;
                    }else skip(reader,2);
                }
                reader.endObject();
            }
            reader.endObject();if(reader.peek()!=JsonToken.END_DOCUMENT)throw new IOException("Trailing texture metadata");
        }catch(IllegalStateException failure){throw new IOException("Invalid texture metadata",failure);}
        return new PackTextureMetadata(blur,clamp);
    }
    private static void skip(JsonReader reader,int depth)throws IOException{
        if(depth>16)throw new IOException("Texture metadata nesting exceeds 16");
        switch(reader.peek()){
            case BEGIN_OBJECT->{reader.beginObject();while(reader.hasNext()){reader.nextName();skip(reader,depth+1);}reader.endObject();}
            case BEGIN_ARRAY->{reader.beginArray();while(reader.hasNext())skip(reader,depth+1);reader.endArray();}
            case STRING,NUMBER->reader.nextString();
            case BOOLEAN->reader.nextBoolean();
            case NULL->reader.nextNull();
            default->throw new IOException("Invalid texture metadata value");
        }
    }
}
