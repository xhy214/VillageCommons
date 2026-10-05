package io.github.xhy214.village.data;
import com.google.gson.*;
import io.github.xhy214.village.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StateStoreTest {
    @TempDir Path directory;
    @Test void aggressiveStateAndMemorySurviveRestart() throws Exception {
        WorldState s=new CommonsEngine().prepare(WorldState.initial(WorldState.AiProfile.GREEDY));
        s.agents.getFirst().remember("R1个人储备");StateStore store=new StateStore(directory);store.save(s);
        WorldState loaded=store.load();assertEquals(WorldState.AiProfile.GREEDY,loaded.aiProfile);
        assertEquals(1,loaded.round);assertEquals(48,loaded.mature());
        assertEquals("R1个人储备",loaded.agents.getFirst().memory.getFirst());
        assertTrue(loaded.agents.getFirst().personality.contains("缺乏信任"));
    }
    @Test void oldGovernedSaveMigratesWithoutCarryingRestrictionsIntoTheNewRound() throws Exception {
        JsonObject legacy=new Gson().toJsonTree(WorldState.initial()).getAsJsonObject();
        legacy.addProperty("schemaVersion",2);legacy.remove("aiProfile");
        legacy.addProperty("governance","AUTO");legacy.addProperty("policyLimit",0);
        legacy.addProperty("policyFromRound",1);legacy.addProperty("policyThroughRound",3);
        JsonObject pending=new JsonObject();pending.addProperty("limit",0);legacy.add("pending",pending);
        legacy.getAsJsonArray("agents").get(0).getAsJsonObject().add("memory",new Gson().toJsonTree(List.of("R0 意图2 投票yes")));
        Files.writeString(directory.resolve("state.json"),legacy.toString());
        StateStore store=new StateStore(directory);WorldState loaded=store.load();
        assertEquals(3,loaded.schemaVersion);assertEquals(WorldState.AiProfile.NORMAL,loaded.aiProfile);
        assertEquals("R0 意图2",loaded.agents.getFirst().memory.getFirst());
        try(var paths=Files.list(directory)){assertTrue(paths.anyMatch(p->p.getFileName().toString().startsWith("state.v2-backup-")));}
        WorldState p=new CommonsEngine().prepare(loaded);
        var actions=p.agents.stream().map(a->new Decision.Action(a.id,3,"测试")).toList();
        WorldState settled=new CommonsEngine().settle(p,new Decision(actions,"",List.of())).state();
        assertEquals(12,settled.lastHarvest);store.save(settled);
        String saved=Files.readString(directory.resolve("state.json"));assertFalse(saved.contains("policy"));assertFalse(saved.contains("pending"));
    }
    @Test void tamperedResourcesAreBackedUp() throws Exception {
        StateStore store=new StateStore(directory);store.save(WorldState.initial());Path file=directory.resolve("state.json");
        Files.writeString(file,Files.readString(file).replace("\"wheatCreated\":52","\"wheatCreated\":53"));
        assertThrows(java.io.IOException.class,store::load);
        try(var paths=Files.list(directory)){assertTrue(paths.anyMatch(p->p.getFileName().toString().startsWith("state.invalid-")));}
        assertFalse(Files.exists(file));
    }
    @Test void duplicateAgentIsRejected() throws Exception {
        WorldState s=WorldState.initial();s.agents.set(3,s.agents.getFirst().copy());
        assertThrows(RuntimeException.class,()->new StateStore(directory).save(s));
    }
    @Test void changedCsvSchemaArchivesOldMetricsRatherThanMixingColumns() throws Exception {
        Path csv=directory.resolve("metrics.csv");String previous="run_id,governance,yes_votes\nold,AUTO,4\n";
        Files.writeString(csv,previous);WorldState s=WorldState.initial();
        RoundRecord record=new RoundRecord("now","test","AI","",1,10,2,3,true,s,List.of(),List.of(),List.of(),List.of());
        new StateStore(directory).append(record);
        String[] lines=Files.readString(csv).split("\n");
        assertTrue(lines[0].contains("ai_profile"));assertFalse(lines[0].contains("votes"));
        assertEquals(lines[0].split(",").length,lines[1].split(",").length);
        try(var paths=Files.list(directory)){Path backup=paths.filter(p->p.getFileName().toString().startsWith("metrics.legacy-")).findFirst().orElseThrow();assertEquals(previous,Files.readString(backup));}
    }
}
