package io.github.xhy214.village.data;
import io.github.xhy214.village.core.*;
import java.util.List;
public record RoundRecord(String timestamp,String runId,String source,String notice,int apiRequests,long elapsedMs,
                          long promptTokens,long completionTokens,boolean usageReported,WorldState state,
                          List<Decision.Action> decisions,List<CommonsEngine.Harvest> harvests,
                          List<Integer> regrown,List<String> notes) {}
