package io.github.xhy214.village.ai;

import com.google.gson.*;
import java.net.URI;
import java.nio.file.*;
import java.io.IOException;

public record ApiSettings(String endpoint,String apiKey,String model,int timeoutSeconds,int maxTokens,int maxRequests,JsonObject extra) {
    public static ApiSettings empty() { return new ApiSettings("","","",45,1200,30,new JsonObject()); }
    public boolean configured() { return !endpoint.isBlank() && !apiKey.isBlank() && !model.isBlank(); }

    public static ApiSettings read(Path file) throws IOException {
        if (!Files.exists(file)) return empty();
        if (Files.size(file)>32_768) throw new IOException("API 配置文件过大");
        try {
            JsonObject o=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            ApiSettings s=new ApiSettings(string(o,"endpoint"),string(o,"apiKey"),string(o,"model"),
                    number(o,"timeoutSeconds",45,5,120),number(o,"maxTokens",1200,256,8192),number(o,"maxRequests",30,1,1000),
                    o.has("extra")?o.getAsJsonObject("extra"):new JsonObject());
            if (!s.configured()) return s;
            URI uri=URI.create(s.endpoint());String host=uri.getHost();
            boolean local="localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
            if (host==null || uri.getUserInfo()!=null || uri.getFragment()!=null || !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))) throw new IllegalArgumentException();
            if (s.apiKey().contains("\n") || s.apiKey().contains("\r") || s.model().length()>160) throw new IllegalArgumentException();
            return s;
        } catch (RuntimeException e) { throw new IOException("API 配置无效；检查 endpoint / apiKey / model 与数字范围"); }
    }

    private static String string(JsonObject o,String key) { return o.has(key)?o.get(key).getAsString().strip():""; }
    private static int number(JsonObject o,String key,int defaultValue,int min,int max) {
        if (!o.has(key)) return defaultValue;
        if (!o.get(key).getAsString().matches("\\d{1,6}")) throw new IllegalArgumentException();
        int n=o.get(key).getAsInt();if (n<min || n>max)throw new IllegalArgumentException();return n;
    }
}
