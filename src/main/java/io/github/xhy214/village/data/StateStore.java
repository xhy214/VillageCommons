package io.github.xhy214.village.data;
import com.google.gson.*;
import io.github.xhy214.village.core.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public final class StateStore {
    private static final String HEADER="run_id,round,source,ai_profile,mature,before,harvest,regrowth,private_stock,shortage_people,api_requests,latency_ms,prompt_tokens,completion_tokens,usage_reported";
    private final Path directory;private final Gson gson=new GsonBuilder().disableHtmlEscaping().create();
    public StateStore(Path directory) {this.directory=directory;}
    public WorldState load() throws IOException {
        Files.createDirectories(directory);Path file=directory.resolve("state.json");
        if(!Files.exists(file))return WorldState.initial();
        try {
            if(Files.size(file)>262144)throw new IllegalArgumentException();
            WorldState s=gson.fromJson(Files.readString(file),WorldState.class);
            boolean legacy=s!=null&&s.schemaVersion==2;
            if(legacy)s.schemaVersion=3;
            validate(s);
            if(legacy) {
                for(Agent a:s.agents)for(int i=0;i<a.memory.size();i++)a.memory.set(i,a.memory.get(i).replaceAll(" 投票.*$",""));
                s.headline=s.profileName()+" · 已恢复旧版资源";s.lastMode="旧版存档";
                Files.copy(file,directory.resolve("state.v2-backup-"+Instant.now().toEpochMilli()+".json"));
            }
            return s;
        }catch(RuntimeException invalid) {
            Files.move(file,directory.resolve("state.invalid-"+Instant.now().toEpochMilli()+".json"));
            throw new IOException("存档无效，已备份为state.invalid文件；启用新实验");
        }
    }
    private void validate(WorldState s) {
        CommonsEngine.assertInvariants(s);
        if(s.schemaVersion!=3||s.round<0||s.aiProfile==null)throw new IllegalArgumentException();
        Set<String> ids=new HashSet<>();WorldState original=WorldState.initial(s.aiProfile);
        for(Agent a:s.agents) {
            Agent o=original.agent(a.id);
            if(o==null||!ids.add(a.id)||a.dailyNeed!=1||a.wheat>1000000||a.totalHarvest<0||
               a.unmet<0||a.unmet>1||a.hungerRounds<0||a.lastRequested<0||a.lastRequested>3||
               a.lastHarvest<0||a.lastHarvest>3||a.memory==null||a.memory.size()>5)throw new IllegalArgumentException();
            a.name=o.name;a.personality=o.personality;
            for(String m:a.memory)if(m==null||m.length()>240)throw new IllegalArgumentException();
        }
    }
    public void save(WorldState s) throws IOException {
        validate(s);Files.createDirectories(directory);Path tmp=directory.resolve("state.json.tmp");
        Files.writeString(tmp,gson.toJson(s),StandardCharsets.UTF_8);
        try{Files.move(tmp,directory.resolve("state.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException e){Files.move(tmp,directory.resolve("state.json"),StandardCopyOption.REPLACE_EXISTING);}
    }
    public void event(String runId,String action,WorldState s) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("events.jsonl"),gson.toJson(Map.of("timestamp",Instant.now().toString(),"runId",runId,"action",action,"state",s))+"\n",
            StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    public void append(RoundRecord r) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("rounds.jsonl"),gson.toJson(r)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        Path csv=directory.resolve("metrics.csv");WorldState s=r.state();
        if(Files.exists(csv)) {
            String header;try(var reader=Files.newBufferedReader(csv)){header=reader.readLine();}
            if(!HEADER.equals(header))Files.move(csv,directory.resolve("metrics.legacy-"+Instant.now().toEpochMilli()+".csv"));
        }
        if(!Files.exists(csv))Files.writeString(csv,HEADER+"\n",StandardCharsets.UTF_8);
        String row=String.join(",",r.runId(),""+s.round,r.source(),s.aiProfile.name(),""+s.mature(),""+s.lastBefore,
            ""+s.lastHarvest,""+s.lastRegrowth,""+s.stocks(),""+s.lastShortages,""+r.apiRequests(),
            ""+r.elapsedMs(),""+r.promptTokens(),""+r.completionTokens(),""+r.usageReported())+"\n";
        Files.writeString(csv,row,StandardCharsets.UTF_8,StandardOpenOption.APPEND);
    }
}
