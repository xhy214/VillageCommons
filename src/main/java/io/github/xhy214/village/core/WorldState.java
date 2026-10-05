package io.github.xhy214.village.core;
import java.util.*;

public final class WorldState {
    public enum AiProfile { NORMAL, GREEDY }
    public int schemaVersion=3, round;
    public List<Agent> agents=new ArrayList<>();
    // x,z=-3..3 excluding the central water: 48 hydrated crop positions.
    public boolean[] ripe=new boolean[48];
    public AiProfile aiProfile=AiProfile.NORMAL;
    public int lastHarvest, lastRegrowth, lastShortages, lastBefore;
    public long wheatCreated=52, wheatConsumed;
    public String headline="共享麦田 · 等待第一次收割", lastMode="等待开始";

    public static WorldState initial() {return initial(AiProfile.NORMAL);}
    public static WorldState initial(AiProfile profile) {
        WorldState s=new WorldState();Arrays.fill(s.ripe,true);
        s.aiProfile=Objects.requireNonNull(profile);
        boolean greedy=profile==AiProfile.GREEDY;
        s.add("farmer_1","阿穗",greedy?"担心别人抢先收割；优先囤足个人口粮，对他人的克制缺乏信任":"谨慎；重视未来粮源，口粮充足时愿意保留麦株");
        s.add("farmer_2","老谷",greedy?"强烈看重当期收获和个人储备；愿意承担公共粮源衰退的风险":"重视稳定收获；会依据库存和麦田变化调整，避免失去未来粮源");
        s.add("farmer_3","小禾",greedy?"重视公平但怕自己吃亏；别人多收时倾向跟进，优先保障自己":"愿意适度收割；关心其他人的口粮和全村长期供应");
        s.add("farmer_4","阿商",greedy?"偏好扩大私人储备；将未来收益看得较轻，会利用收割机会，也会在危机时重新权衡":"善于权衡；兼顾个人储备与长期收益，观察他人行为后自主选择");
        s.headline=s.profileName()+" · 等待第一次收割";
        return s;
    }
    private void add(String id,String name,String personality) {Agent a=new Agent();a.id=id;a.name=name;a.personality=personality;agents.add(a);}
    public Agent agent(String id) {return agents.stream().filter(a->a.id.equals(id)).findFirst().orElse(null);}
    public int mature() {int n=0;for(boolean p:ripe)if(p)n++;return n;}
    public int stocks() {return agents.stream().mapToInt(a->a.wheat).sum();}
    public String profileName() {return aiProfile==AiProfile.GREEDY?"激进AI":"普通AI";}
    public WorldState copy() {
        WorldState s=new WorldState();
        s.schemaVersion=schemaVersion;s.round=round;
        s.agents=agents.stream().map(Agent::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        s.ripe=ripe.clone();s.aiProfile=aiProfile;
        s.lastHarvest=lastHarvest;s.lastRegrowth=lastRegrowth;s.lastShortages=lastShortages;s.lastBefore=lastBefore;
        s.wheatCreated=wheatCreated;s.wheatConsumed=wheatConsumed;s.headline=headline;s.lastMode=lastMode;return s;
    }
}
