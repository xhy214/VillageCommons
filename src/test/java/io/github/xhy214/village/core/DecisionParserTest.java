package io.github.xhy214.village.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DecisionParserTest {
    final WorldState state=WorldState.initial();final DecisionParser parser=new DecisionParser();
    String a(String id,String amount){return "{\"id\":\""+id+"\",\"harvest\":"+amount+",\"reason\":\"自保\"}";}
    String all(String amount) {return "["+a("farmer_1",amount)+","+a("farmer_2",amount)+","+a("farmer_3",amount)+","+a("farmer_4",amount)+"]";}
    @Test void fourCompleteActionsAreAccepted() {
        var d=parser.parse("{\"actions\":"+all("2")+",\"headline\":\"观察\"}",state);
        assertEquals(4,d.actions().size());assertTrue(d.actions().stream().allMatch(v->v.harvest()==2));assertEquals("观察",d.headline());
    }
    @Test void oneInvalidQuantityRejectsTheWholeRound() {
        String text="{\"actions\":["+a("farmer_1","9")+","+a("farmer_2","2")+","+a("farmer_3","1")+","+a("farmer_4","0")+"]}";
        assertThrows(RuntimeException.class,()->parser.parse(text,state));
    }
    @Test void duplicateIdRejectsTheWholeRound() {
        String text="{\"actions\":["+a("farmer_1","1")+","+a("farmer_1","3")+","+a("farmer_3","2")+","+a("farmer_4","2")+"]}";
        assertThrows(RuntimeException.class,()->parser.parse(text,state));
    }
    @Test void obsoleteProposalCannotChangeTheHarvest() {
        var d=parser.parse("{\"actions\":"+all("3")+",\"proposal\":{\"proposer_id\":\"farmer_1\",\"limit\":0,\"duration\":3,\"reason\":\"旧协议\"}}",state);
        var s=new CommonsEngine().settle(new CommonsEngine().prepare(state),d).state();
        assertEquals(12,s.lastHarvest);assertEquals(45,s.mature());
    }
    @Test void refusalIsNotTreatedAsDecision() {assertThrows(RuntimeException.class,()->parser.parse("无法返回JSON",state));}
    @Test void fractionalQuantityRejectsTheWholeRound() {
        assertThrows(RuntimeException.class,()->parser.parse("{\"actions\":"+all("1.5")+"}",state));
    }
    @Test void missingAgentRejectsTheWholeRound() {
        String text="{\"actions\":["+a("farmer_1","2")+","+a("farmer_2","2")+","+a("farmer_3","2")+"]}";
        assertThrows(RuntimeException.class,()->parser.parse(text,state));
    }
    @Test void unknownAgentRejectsTheWholeRound() {
        String text="{\"actions\":["+a("outsider","2")+","+a("farmer_2","2")+","+a("farmer_3","2")+","+a("farmer_4","2")+"]}";
        assertThrows(RuntimeException.class,()->parser.parse(text,state));
    }
}
