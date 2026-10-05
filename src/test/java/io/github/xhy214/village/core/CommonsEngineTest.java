package io.github.xhy214.village.core;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommonsEngineTest {
    final CommonsEngine engine=new CommonsEngine();
    Decision request(WorldState s,int n) {
        return new Decision(s.agents.stream().map(a->new Decision.Action(a.id,n,"测试")).toList(),"测试",List.of());
    }
    WorldState run(WorldState s,int n) {WorldState p=engine.prepare(s);return engine.settle(p,request(p,n)).state();}
    @Test void fieldHasFortyEightHydratedCropPositions() {
        Set<String> cells=new HashSet<>();
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)if(x!=0||z!=0){assertTrue(Math.abs(x)<=4&&Math.abs(z)<=4);cells.add(x+","+z);}
        assertEquals(48,cells.size());assertEquals(48,WorldState.initial().mature());
    }
    @Test void repeatedThreeCropChoicesDepleteTheFieldOnSeventhRound() {
        WorldState s=WorldState.initial();
        for(int expected:new int[]{45,41,36,30,22,12,0}){s=run(s,3);assertEquals(expected,s.mature());CommonsEngine.assertInvariants(s);}
        assertEquals(7,s.round);assertEquals(21,s.agents.getFirst().totalHarvest);assertEquals(15,s.agents.getFirst().wheat);
    }
    @Test void OneOrTwoPerPersonCanRemainSustainable() {
        for(int qty:new int[]{1,2}) {
            WorldState s=WorldState.initial();
            for(int i=0;i<30;i++){s=run(s,qty);assertEquals(48,s.mature());assertEquals(0,s.lastShortages);}
        }
    }
    @Test void highHarvestThresholdStartsAtTenWhenFieldIsFull() {
        for(int total=8;total<=12;total++) {
            WorldState p=engine.prepare(WorldState.initial());List<Decision.Action> actions=new ArrayList<>();
            int remaining=total;
            for(Agent a:p.agents){int n=Math.min(3,remaining);remaining-=n;actions.add(new Decision.Action(a.id,n,""));}
            WorldState s=engine.settle(p,new Decision(actions,"",List.of())).state();
            assertEquals(total,s.lastHarvest);assertEquals(total<=9?48:57-total,s.mature());
        }
    }
    @Test void noHarvestCanRestoreADegradedField() {
        WorldState s=run(run(WorldState.initial(),3),3);assertEquals(41,s.mature());
        s=run(s,0);assertEquals(48,s.mature());assertEquals(7,s.lastRegrowth);
    }
    @Test void twoPerPersonDoesNotAutomaticallyRepairAnAlreadyDegradedField() {
        WorldState s=WorldState.initial();for(int i=0;i<4;i++)s=run(s,3);
        assertEquals(30,s.mature());s=run(s,2);assertEquals(27,s.mature());
    }
    @Test void depletionDoesNotInventGrowthAndPrivateStocksDelayHunger() {
        WorldState s=WorldState.initial();for(int i=0;i<7;i++)s=run(s,3);
        assertEquals(0,s.mature());assertEquals(0,s.lastShortages);
        for(int i=0;i<16;i++)s=run(s,3);
        assertEquals(0,s.mature());assertEquals(0,s.lastRegrowth);assertEquals(4,s.lastShortages);
    }
    @Test void roundPriorityRotatesForScarceHarvest() {
        Set<String> winners=new HashSet<>();
        for(int round=0;round<4;round++) {
            WorldState s=WorldState.initial();Arrays.fill(s.ripe,false);s.ripe[0]=true;s.round=round;s.wheatCreated=5;
            s=run(s,3);assertEquals(1,s.lastHarvest);
            for(Agent a:s.agents)if(a.lastHarvest>0)winners.add(a.id);
        }
        assertEquals(4,winners.size());
    }
    @Test void profileChangesDoNotHardCodeTheHarvestOrResourceFormula() {
        for(int n=0;n<=3;n++) {
            WorldState normal=run(WorldState.initial(WorldState.AiProfile.NORMAL),n);
            WorldState greedy=run(WorldState.initial(WorldState.AiProfile.GREEDY),n);
            assertEquals(normal.mature(),greedy.mature());assertEquals(normal.lastHarvest,greedy.lastHarvest);
            assertEquals(normal.stocks(),greedy.stocks());assertEquals(normal.wheatConsumed,greedy.wheatConsumed);
        }
    }
    @Test void inputStateIsNotMutated() {
        WorldState original=WorldState.initial();WorldState prepared=engine.prepare(original);run(prepared,3);
        assertEquals(0,original.round);assertEquals(48,original.mature());assertEquals(1,original.agents.getFirst().wheat);
    }
    @Test void missingDecisionDoesNotAlterPreparedResources() {
        WorldState p=engine.prepare(WorldState.initial());
        Decision incomplete=new Decision(List.of(new Decision.Action("farmer_1",3,"测试")),"",List.of());
        assertThrows(RuntimeException.class,()->engine.settle(p,incomplete));
        assertEquals(48,p.mature());assertEquals(4,p.stocks());assertEquals(0,p.wheatConsumed);
        assertTrue(p.agents.stream().allMatch(a->a.memory.isEmpty()));
    }
    @Test void longRandomRunPreservesResourcesAndMemoryBounds() {
        Random random=new Random(214);WorldState s=WorldState.initial();
        for(int i=0;i<600;i++) {
            WorldState p=engine.prepare(s);
            var actions=p.agents.stream().map(a->new Decision.Action(a.id,random.nextInt(4),"")).toList();
            s=engine.settle(p,new Decision(actions,"",List.of())).state();
            CommonsEngine.assertInvariants(s);assertTrue(s.mature()<=48);assertTrue(s.agents.stream().allMatch(a->a.memory.size()<=5));
        }
    }
}
