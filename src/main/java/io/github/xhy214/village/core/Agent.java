package io.github.xhy214.village.core;
import java.util.*;

public final class Agent {
    public String id, name, personality;
    public int wheat=1, dailyNeed=1, unmet, hungerRounds, lastRequested, lastHarvest;
    public long totalHarvest;
    public String lastReason="等待第一次收割",decisionSource="尚未决策";
    public List<String> memory=new ArrayList<>();
    public Agent copy() {
        Agent a=new Agent();a.id=id;a.name=name;a.personality=personality;a.wheat=wheat;a.dailyNeed=dailyNeed;
        a.unmet=unmet;a.hungerRounds=hungerRounds;a.lastRequested=lastRequested;a.lastHarvest=lastHarvest;
        a.totalHarvest=totalHarvest;a.lastReason=lastReason;a.decisionSource=decisionSource;
        a.memory=new ArrayList<>(memory);return a;
    }
    public void remember(String message) {memory.add(message);while(memory.size()>5)memory.removeFirst();}
}
