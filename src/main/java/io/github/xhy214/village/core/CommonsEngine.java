package io.github.xhy214.village.core;
import java.util.*;

/** Pure resource simulation. The same allocation and regrowth apply to both AI profiles. */
public final class CommonsEngine {
    public record Harvest(String id,int plot) {}
    public record Outcome(WorldState state,List<Harvest> harvests,List<Integer> regrown,List<String> notes) {}
    public WorldState prepare(WorldState original) {WorldState s=original.copy();s.round++;return s;}
    public Outcome settle(WorldState prepared,Decision decision) {
        WorldState s=prepared.copy();int before=s.mature();
        Map<String,Decision.Action> inputs=new HashMap<>();
        if(decision==null||decision.actions()==null||decision.actions().size()!=4)throw new IllegalArgumentException("Four complete AI decisions required");
        for(var action:decision.actions()) {
            if(action==null||s.agent(action.id())==null||action.harvest()<0||action.harvest()>3||
               inputs.putIfAbsent(action.id(),action)!=null)throw new IllegalArgumentException("Invalid AI decision");
        }
        int[] left=new int[4];
        for(int i=0;i<4;i++) {
            Agent agent=s.agents.get(i);Decision.Action action=inputs.get(agent.id);
            if(action==null)throw new IllegalArgumentException("Missing AI decision");
            agent.lastRequested=action.harvest();agent.lastHarvest=0;agent.lastReason=action.reason();
            agent.decisionSource="AI";left[i]=action.harvest();
        }
        // Allocate one crop per person per pass; priority rotates each round.
        List<Harvest> harvests=new ArrayList<>();
        int cursor=Math.floorMod(s.round*11,48);
        for(int pass=0;pass<3;pass++)for(int k=0;k<4;k++) {
            int i=Math.floorMod(k+s.round-1,4);if(left[i]<=0)continue;
            int plot=-1;for(int n=0;n<48;n++)if(s.ripe[(cursor+n)%48]){plot=(cursor+n)%48;break;}
            if(plot<0)continue;
            s.ripe[plot]=false;cursor=(plot+1)%48;left[i]--;
            Agent agent=s.agents.get(i);agent.wheat++;agent.lastHarvest++;agent.totalHarvest++;
            harvests.add(new Harvest(agent.id,plot));
        }
        int remaining=s.mature(),growth=Math.min(48-remaining,remaining/4);
        List<Integer> regrown=new ArrayList<>();cursor=Math.floorMod(s.round*17,48);
        for(int n=0;n<48&&regrown.size()<growth;n++) {
            int plot=(cursor+n)%48;if(!s.ripe[plot]){s.ripe[plot]=true;regrown.add(plot);}
        }
        s.wheatCreated+=regrown.size();s.lastBefore=before;s.lastHarvest=harvests.size();s.lastRegrowth=regrown.size();
        s.lastShortages=0;
        for(Agent agent:s.agents) {
            int eaten=Math.min(agent.dailyNeed,agent.wheat);agent.wheat-=eaten;s.wheatConsumed+=eaten;
            agent.unmet=agent.dailyNeed-eaten;agent.hungerRounds=agent.unmet>0?agent.hungerRounds+1:0;
            if(agent.unmet>0)s.lastShortages++;
            agent.remember("R"+s.round+" 意图"+agent.lastRequested+" 实收"+agent.lastHarvest+" 库存"+agent.wheat+" 缺粮"+agent.unmet+" 麦田"+s.mature());
        }
        s.headline=decision.headline();s.lastMode="AI";
        assertInvariants(s);return new Outcome(s,List.copyOf(harvests),List.copyOf(regrown),decision.warnings());
    }
    public static void assertInvariants(WorldState s) {
        if(s==null||s.ripe==null||s.ripe.length!=48||s.agents==null||s.agents.size()!=4||
           s.wheatConsumed<0||s.wheatCreated<0||s.agents.stream().anyMatch(a->a==null||a.wheat<0))
            throw new IllegalStateException("Invalid resource state");
        long total=s.mature()+s.agents.stream().mapToLong(a->a.wheat).sum()+s.wheatConsumed;
        if(total!=s.wheatCreated)throw new IllegalStateException("Grain conservation failed");
    }
}
