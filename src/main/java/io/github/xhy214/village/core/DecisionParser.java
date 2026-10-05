package io.github.xhy214.village.core;
import com.google.gson.*;
import java.util.*;

public final class DecisionParser {
    public Decision parse(String content,WorldState s) {
        String text=content.strip();
        if(text.startsWith("~~~")||text.startsWith("\u0060\u0060\u0060")) {
            int begin=text.indexOf('\n'),end=text.lastIndexOf(text.substring(0,3));
            if(begin>=0&&end>begin)text=text.substring(begin+1,end).strip();
        }
        if(text.length()>16000)throw new IllegalArgumentException("Output too long");
        JsonObject root=JsonParser.parseString(text).getAsJsonObject();JsonArray input=root.getAsJsonArray("actions");
        if(input==null||input.size()!=4)throw new IllegalArgumentException("Exactly four actions are required");
        Map<String,Decision.Action> valid=new HashMap<>();
        for(JsonElement element:input) {
            JsonObject action=element.getAsJsonObject();String id=string(action,"id",24);
            if(s.agent(id)==null||valid.containsKey(id))throw new IllegalArgumentException("Unknown or duplicate agent");
            valid.put(id,new Decision.Action(id,integer(action,"harvest",0,3),string(action,"reason",36)));
        }
        List<Decision.Action> actions=new ArrayList<>();
        for(Agent agent:s.agents) {
            Decision.Action action=valid.get(agent.id);
            if(action==null)throw new IllegalArgumentException("Missing agent decision");
            actions.add(action);
        }
        String headline;try{headline=string(root,"headline",36);}catch(RuntimeException e){headline="共享麦田 · 第"+s.round+"回合";}
        return new Decision(List.copyOf(actions),headline,List.of());
    }
    private int integer(JsonObject object,String key,int min,int max) {
        JsonElement value=object.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||
           !value.getAsString().matches("-?\\d{1,6}"))throw new IllegalArgumentException();
        int number=value.getAsInt();if(number<min||number>max)throw new IllegalArgumentException();return number;
    }
    private String string(JsonObject object,String key,int limit) {
        JsonElement value=object.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException();
        return clean(value.getAsString(),limit);
    }
    public static String clean(String text,int max) {
        if(text==null)return "";text=text.replaceAll("[\\p{Cc}\\p{Cf}§]"," ").strip();
        int count=text.codePointCount(0,text.length());return count>max?text.substring(0,text.offsetByCodePoints(0,max)):text;
    }
}
