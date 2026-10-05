package io.github.xhy214.village.ai;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import io.github.xhy214.village.core.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ApiClientTest {
    @Test void promptOnlySharesHarvestStateAndRespectsBothAiProfiles() {
        WorldState s=WorldState.initial();JsonObject wire=JsonParser.parseString(FieldPrompt.snapshot(s)).getAsJsonObject();
        assertEquals("normal",wire.get("ai_profile").getAsString());
        assertFalse(wire.has("governance"));assertFalse(wire.has("pending_proposal"));assertFalse(wire.has("current_harvest_limit"));
        assertTrue(FieldPrompt.system(s).contains("普通AI性格实验"));
        assertFalse(FieldPrompt.system(s).contains("投票"));assertFalse(FieldPrompt.system(s).contains("提案"));
        assertTrue(FieldPrompt.system(WorldState.initial(WorldState.AiProfile.GREEDY)).contains("AI激进性格实验"));
    }
    String output() {
        JsonArray actions=new JsonArray();
        for(Agent a:WorldState.initial().agents) {
            JsonObject o=new JsonObject();o.addProperty("id",a.id);o.addProperty("harvest",2);
            o.addProperty("reason","留粮");actions.add(o);
        }
        JsonObject o=new JsonObject();o.add("actions",actions);o.addProperty("headline","测试");return o.toString();
    }
    ApiSettings cfg(int port,int max){return new ApiSettings("http://127.0.0.1:"+port+"/chat/completions","mock-only","mock-model",5,1200,max,new JsonObject());}
    String envelope(String content) {
        JsonObject root=new JsonObject(),msg=new JsonObject(),choice=new JsonObject(),usage=new JsonObject();JsonArray list=new JsonArray();
        msg.addProperty("content",content);choice.add("message",msg);choice.addProperty("finish_reason","stop");list.add(choice);
        root.add("choices",list);usage.addProperty("prompt_tokens",100);usage.addProperty("completion_tokens",80);root.add("usage",usage);return root.toString();
    }
    @Test void unsupportedParametersRetryOnceThenPlainProfileIsRetained() throws Exception {
        List<JsonObject> bodies=Collections.synchronizedList(new ArrayList<>());AtomicInteger n=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{
            JsonObject body=JsonParser.parseString(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();bodies.add(body);
            boolean first=n.getAndIncrement()==0;
            byte[] data=(first?"{\"error\":\"unsupported parameter response_format\"}":envelope(output())).getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(first?400:200,data.length);e.getResponseBody().write(data);e.close();
        });server.start();
        try(ApiClient api=new ApiClient(cfg(server.getAddress().getPort(),10))) {
            WorldState greedy=WorldState.initial(WorldState.AiProfile.GREEDY);
            var one=api.decide(greedy);assertEquals("AI",one.source());assertEquals(2,one.requests());
            var two=api.decide(greedy);assertEquals(1,two.requests());
            assertTrue(bodies.getFirst().has("response_format"));assertTrue(bodies.stream().skip(1).noneMatch(o->o.has("response_format")));
            assertEquals("plain",api.status().parameters());assertTrue(api.status().parametersAccepted());assertEquals(200,api.status().promptTokens());
            for(JsonObject body:bodies) {
                JsonArray messages=body.getAsJsonArray("messages");
                assertTrue(messages.get(0).getAsJsonObject().get("content").getAsString().contains("AI激进性格实验"));
                JsonObject snapshot=JsonParser.parseString(messages.get(1).getAsJsonObject().get("content").getAsString()).getAsJsonObject();
                assertEquals("greedy",snapshot.get("ai_profile").getAsString());
                assertTrue(snapshot.getAsJsonArray("agents").get(0).getAsJsonObject().get("personality").getAsString().contains("缺乏信任"));
            }
            var settled=new CommonsEngine().settle(new CommonsEngine().prepare(greedy),two.decision());
            assertEquals(8,settled.state().lastHarvest,"AI returned 2 each; greedy profile must not force 3");
            assertEquals(WorldState.AiProfile.GREEDY,settled.state().aiProfile);
        }finally{server.stop(0);}
    }
    @Test void authenticationFailureDoesNotTriggerParameterRetry() throws Exception {
        AtomicInteger count=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{count.incrementAndGet();byte[] data="{\"error\":\"invalid key\"}".getBytes();e.sendResponseHeaders(401,data.length);e.getResponseBody().write(data);e.close();});server.start();
        try(ApiClient api=new ApiClient(cfg(server.getAddress().getPort(),10))) {
            var result=api.decide(WorldState.initial());assertFalse(result.success());assertNull(result.decision());assertEquals("AI_ERROR",result.source());assertEquals(1,count.get());
        }finally{server.stop(0);}
    }
    @Test void malformedContentFailsWithoutDecisionButReportedUsageIsRetained() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{byte[] data=envelope("暂时无法返回约定格式").getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);e.close();});server.start();
        try(ApiClient api=new ApiClient(cfg(server.getAddress().getPort(),10))) {
            var r=api.decide(WorldState.initial());assertFalse(r.success());assertNull(r.decision());assertEquals("AI_ERROR",r.source());assertTrue(r.usageReported());assertEquals(80,r.completionTokens());
        }finally{server.stop(0);}
    }
    @Test void incompleteModelDecisionsCannotBecomePartialAiOrRules() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        JsonObject incomplete=JsonParser.parseString(output()).getAsJsonObject();incomplete.getAsJsonArray("actions").remove(3);
        server.createContext("/",e->{byte[] data=envelope(incomplete.toString()).getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);e.close();});server.start();
        try(ApiClient api=new ApiClient(cfg(server.getAddress().getPort(),10))) {
            var result=api.decide(WorldState.initial());assertFalse(result.success());assertNull(result.decision());
            assertTrue(result.usageReported());assertEquals(0,api.status().successfulRounds());
        }finally{server.stop(0);}
    }
    @Test void truncatedOutputFailsEvenWhenContentLooksLikeCompleteJson() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String response=envelope(output()).replace("\"finish_reason\":\"stop\"","\"finish_reason\":\"length\"");
        server.createContext("/",e->{byte[] data=response.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);e.close();});server.start();
        try(ApiClient api=new ApiClient(cfg(server.getAddress().getPort(),10))) {
            var result=api.decide(WorldState.initial());assertFalse(result.success());assertNull(result.decision());assertTrue(result.notice().contains("长度上限"));
        }finally{server.stop(0);}
    }
    @Test void missingConfigurationFailsWithoutDecisionOrNetworkRequest() throws Exception {
        try(ApiClient api=new ApiClient(ApiSettings.empty())) {
            var result=api.decide(WorldState.initial());assertFalse(result.success());assertNull(result.decision());
            assertEquals(0,result.requests());assertEquals(0,api.status().requests());
        }
    }
    @Test void requestLimitIsNotResetByConfigurationReload() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{byte[] data=envelope(output()).getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);e.close();});server.start();
        try(ApiClient api=new ApiClient(cfg(server.getAddress().getPort(),1))) {
            assertEquals("AI",api.decide(WorldState.initial()).source());api.configure(cfg(server.getAddress().getPort(),1));
            var result=api.decide(WorldState.initial());assertFalse(result.success());assertNull(result.decision());
            assertEquals(0,result.requests());assertEquals(1,api.status().requests());
            api.configure(cfg(server.getAddress().getPort(),2));assertTrue(api.decide(WorldState.initial()).success());
            assertEquals(2,api.status().requests());
        }finally{server.stop(0);}
    }
}
