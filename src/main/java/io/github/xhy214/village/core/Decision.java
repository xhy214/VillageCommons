package io.github.xhy214.village.core;
import java.util.List;
public record Decision(List<Action> actions,String headline,List<String> warnings) {
    public record Action(String id,int harvest,String reason) {}
}
