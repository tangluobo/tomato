package com.tangluobo.tomato.module.connect.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tangluobo.tomato.module.connect.GlobalConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/** 调用 OpenAI 兼容接口，根据数据库结构和自然语言生成 SQL。 */
public final class AiSqlService {
    private static final Pattern SQL_START = Pattern.compile(
            "(?is)^\\s*(?:(?:--[^\\r\\n]*(?:\\r?\\n|$))|(?:/\\*.*?\\*/\\s*))*"
                    + "(select|with|insert|update|delete|merge|create|alter|drop|truncate|explain|show|desc|describe|call)\\b");

    private AiSqlService() {
    }

    public static String generateSql(GlobalConfig config,
                                     String dialect,
                                     String database,
                                     String schema,
                                     String request,
                                     String selectedSql,
                                     String schemaContext) throws Exception {
        if (config == null || !config.isAiSqlConfigured()) {
            throw new IllegalStateException("请先配置 AI SQL 的接口地址和模型");
        }
        int timeoutSeconds = config.getAiRequestTimeoutSeconds();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 30)))
                .build();

        JsonObject payload = new JsonObject();
        payload.addProperty("model", config.getAiModel());
        payload.addProperty("stream", false);
        JsonArray messages = new JsonArray();
        messages.add(message("system", buildSystemPrompt(dialect, database, schema, schemaContext)));
        messages.add(message("user", buildUserPrompt(request, selectedSql)));
        payload.add("messages", messages);

        HttpRequest.Builder httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(chatCompletionsUrl(config.getAiApiBaseUrl())))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));
        String apiKey = config.getAiApiKey();
        if (!apiKey.isBlank()) httpRequest.header("Authorization", "Bearer " + apiKey);

        HttpResponse<String> response = client.send(httpRequest.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("模型接口返回 HTTP " + response.statusCode() + "："
                    + extractError(response.body()));
        }
        String sql = cleanSql(extractContent(response.body()));
        if (sql.isBlank()) throw new IllegalStateException("模型没有返回 SQL");
        if (!SQL_START.matcher(sql).find()) {
            throw new IllegalStateException("模型返回的内容不像 SQL，请调整提示后重试");
        }
        return sql;
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static String buildSystemPrompt(String dialect,
                                            String database,
                                            String schema,
                                            String schemaContext) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是数据库 SQL 助手。只返回一条可直接放入编辑器的 SQL，不要 Markdown 代码块、解释或前后说明。\n")
                .append("必须使用给定数据库实际存在的表名和字段名，并遵循 ")
                .append(blankAs(dialect, "当前数据库"))
                .append(" 方言。用户没有明确要求写操作时，只生成只读查询。不要执行 SQL。\n")
                .append("当前数据库：").append(blankAs(database, "未指定"));
        if (schema != null && !schema.isBlank()) prompt.append("，Schema：").append(schema);
        if (schemaContext != null && !schemaContext.isBlank()) {
            prompt.append("\n\n当前库结构（只用于生成 SQL）：\n").append(schemaContext);
        } else {
            prompt.append("\n当前没有可用的表结构；无法确定名称时不要凭空编造，请在 SQL 注释中指出待替换名称。");
        }
        return prompt.toString();
    }

    private static String buildUserPrompt(String request, String selectedSql) {
        StringBuilder prompt = new StringBuilder("需求：").append(request == null ? "" : request.trim());
        if (selectedSql != null && !selectedSql.isBlank()) {
            prompt.append("\n\n需要参考或修改的现有 SQL：\n").append(selectedSql.trim());
        }
        return prompt.toString();
    }

    private static String chatCompletionsUrl(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.toLowerCase(Locale.ROOT).endsWith("/chat/completions")) return url;
        return url + "/chat/completions";
    }

    private static String extractContent(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("模型响应中没有 choices");
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null || !message.has("content")) {
            throw new IllegalStateException("模型响应中没有文本内容");
        }
        JsonElement content = message.get("content");
        if (content.isJsonPrimitive()) return content.getAsString();
        if (content.isJsonArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonElement part : content.getAsJsonArray()) {
                if (!part.isJsonObject()) continue;
                JsonObject object = part.getAsJsonObject();
                if (object.has("text")) text.append(object.get("text").getAsString());
            }
            return text.toString();
        }
        return "";
    }

    private static String extractError(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject error = root.getAsJsonObject("error");
            if (error != null && error.has("message")) return shorten(error.get("message").getAsString());
        } catch (Exception ignored) {
        }
        return shorten(body == null || body.isBlank() ? "未知错误" : body);
    }

    static String cleanSql(String content) {
        if (content == null) return "";
        String value = content.trim();
        if (value.startsWith("```") && value.endsWith("```")) {
            int firstLineEnd = value.indexOf('\n');
            if (firstLineEnd >= 0) value = value.substring(firstLineEnd + 1, value.length() - 3).trim();
        }
        if (value.regionMatches(true, 0, "SQL:", 0, 4)) value = value.substring(4).trim();
        return value;
    }

    private static String shorten(String value) {
        String oneLine = value.replace('\r', ' ').replace('\n', ' ').trim();
        return oneLine.length() <= 500 ? oneLine : oneLine.substring(0, 500) + "…";
    }

    private static String blankAs(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
