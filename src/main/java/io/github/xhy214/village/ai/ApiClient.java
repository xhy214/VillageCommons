package io.github.xhy214.village.ai;

import com.google.gson.*;
import io.github.xhy214.village.core.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Used on one worker thread. Never calls a Minecraft API or exposes credentials. */
public final class ApiClient implements AutoCloseable {
    public record Result(Decision decision,String source,String notice,int requests,long elapsedMs,long promptTokens,long completionTokens,boolean usageReported) {
        public boolean success(){return decision!=null&&source.equals("AI");}
    }
    public record Status(boolean configured,int requests,int requestLimit,int successfulRounds,long promptTokens,long completionTokens,String parameters,boolean parametersAccepted) { }
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Gson gson=new Gson();
    private final AtomicInteger requests=new AtomicInteger(),successes=new AtomicInteger();
    private final AtomicLong promptTokens=new AtomicLong(),completionTokens=new AtomicLong();
    private volatile ApiSettings settings;
    private volatile boolean plainMode;
    private volatile boolean parametersAccepted;

    public ApiClient(ApiSettings settings) { this.settings=settings; }
    public void configure(ApiSettings s) { settings=s;plainMode=false;parametersAccepted=false; }
    public Status status() {
        ApiSettings s=settings;
        return new Status(s.configured(),requests.get(),s.maxRequests(),successes.get(),promptTokens.get(),completionTokens.get(),plainMode?"plain":"optimized",parametersAccepted);
    }

    public Result decide(WorldState state) throws InterruptedException {
        long started=System.nanoTime(),inputBefore=promptTokens.get(),outputBefore=completionTokens.get();int before=requests.get();
        ApiSettings cfg=settings;
        if (!cfg.configured()) return failure("未配置 API，请填写配置后重载",before,started,inputBefore,outputBefore,false);
        if (requests.get()>=cfg.maxRequests()) return failure("本次启动的 API 请求额度已用完，请提高maxRequests后重载",before,started,inputBefore,outputBefore,false);
        boolean usage=false;
        try {
            long deadline=started+cfg.timeoutSeconds()*1_000_000_000L;
            HttpResponse<String> response=send(state,cfg,!plainMode,deadline);
            if ((response.statusCode()==400 || response.statusCode()==422) && !plainMode && unsupported(response.body())) {
                // One compatibility retry. Remember the accepted plain profile for this configuration.
                plainMode=true;response=send(state,cfg,false,deadline);
            }
            if (response.statusCode()<200 || response.statusCode()>=300) throw new ApiFailure("API 返回 HTTP "+response.statusCode());
            parametersAccepted=true;
            if (response.body().length()>131_072) throw new ApiFailure("API 返回内容过大");
            JsonObject envelope=JsonParser.parseString(response.body()).getAsJsonObject();
            if (envelope.has("usage") && envelope.get("usage").isJsonObject()) {
                JsonObject u=envelope.getAsJsonObject("usage");
                if (u.has("prompt_tokens") && u.has("completion_tokens")) {
                    long in=u.get("prompt_tokens").getAsLong(),out=u.get("completion_tokens").getAsLong();
                    if (in>=0 && out>=0) { promptTokens.addAndGet(in);completionTokens.addAndGet(out);usage=true; }
                }
            }
            JsonObject choice=envelope.getAsJsonArray("choices").get(0).getAsJsonObject();
            if (choice.has("finish_reason") && "length".equals(choice.get("finish_reason").getAsString())) throw new ApiFailure("AI输出达到长度上限，请检查maxTokens");
            JsonElement content=choice.getAsJsonObject("message").get("content");
            if (content==null || content.isJsonNull() || !content.isJsonPrimitive()) throw new ApiFailure("模型未返回 JSON 内容");
            Decision decision=new DecisionParser().parse(content.getAsString(),state);
            successes.incrementAndGet();
            return new Result(decision,"AI",plainMode?"兼容模式参数已保持":"优化参数已启用",requests.get()-before,elapsed(started),promptTokens.get()-inputBefore,completionTokens.get()-outputBefore,usage);
        } catch (InterruptedException e) { throw e; }
        catch (Exception e) {
            String notice=e instanceof ApiFailure?e.getMessage():e instanceof HttpTimeoutException?"API请求超时":
                e instanceof IllegalArgumentException?"AI返回的JSON或四名村民决策无效":"API响应暂时无法使用，请检查服务与配置";
            return failure(notice,before,started,inputBefore,outputBefore,usage);
        }
    }

    private HttpResponse<String> send(WorldState state,ApiSettings cfg,boolean optional,long deadline) throws Exception {
        if (requests.get()>=cfg.maxRequests()) throw new ApiFailure("本次启动的 API 请求额度已用完");
        JsonObject body=new JsonObject();body.addProperty("model",cfg.model());body.addProperty("stream",false);
        JsonArray messages=new JsonArray();
        JsonObject system=new JsonObject();system.addProperty("role","system");system.addProperty("content",FieldPrompt.system(state));messages.add(system);
        JsonObject user=new JsonObject();user.addProperty("role","user");user.addProperty("content",FieldPrompt.snapshot(state));messages.add(user);
        body.add("messages",messages);
        if (optional) {
            JsonObject format=new JsonObject();format.addProperty("type","json_object");body.add("response_format",format);
            String host=URI.create(cfg.endpoint()).getHost().toLowerCase(Locale.ROOT),model=cfg.model().toLowerCase(Locale.ROOT);
            body.addProperty(host.equals("api.openai.com")?"max_completion_tokens":"max_tokens",cfg.maxTokens());
            if (host.equals("api.deepseek.com") && model.startsWith("deepseek")) {
                JsonObject thinking=new JsonObject();thinking.addProperty("type","disabled");body.add("thinking",thinking);
            }
            if ((host.equals("open.bigmodel.cn") || host.equals("api.z.ai")) && model.startsWith("glm-5.3")) body.addProperty("reasoning_effort","low");
            Set<String> allowed=Set.of("response_format","max_tokens","max_completion_tokens","thinking","reasoning_effort","enable_thinking","temperature","top_p");
            for (var entry:cfg.extra().entrySet()) if (allowed.contains(entry.getKey())) body.add(entry.getKey(),entry.getValue());
        }
        long remaining=deadline-System.nanoTime();if(remaining<=0)throw new HttpTimeoutException("Round deadline reached");
        HttpRequest request=HttpRequest.newBuilder(URI.create(cfg.endpoint())).timeout(Duration.ofNanos(remaining))
                .header("Content-Type","application/json; charset=utf-8").header("Authorization","Bearer "+cfg.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build();
        requests.incrementAndGet();
        return http.send(request,HttpResponse.BodyHandlers.ofString());
    }

    private boolean unsupported(String body) {
        String b=body.toLowerCase(Locale.ROOT);
        boolean field=List.of("response_format","max_tokens","max_completion_tokens","thinking","reasoning_effort","enable_thinking","temperature","top_p").stream().anyMatch(b::contains);
        boolean reason=List.of("unsupported","not supported","not allowed","unknown parameter","unrecognized","not permitted","invalid parameter","不支持","未知参数").stream().anyMatch(b::contains);
        return field && reason;
    }

    private Result failure(String notice,int before,long started,long in,long out,boolean usage) {
        return new Result(null,"AI_ERROR",notice,requests.get()-before,elapsed(started),promptTokens.get()-in,completionTokens.get()-out,usage);
    }
    private static long elapsed(long start) { return (System.nanoTime()-start)/1_000_000; }
    private static final class ApiFailure extends Exception { ApiFailure(String reason) { super(reason); } }
    @Override public void close() { http.shutdownNow(); }
}
