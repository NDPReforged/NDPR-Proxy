package com.ndpreforged.proxy.common;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP 客户端（JDK 内置 HttpClient，无第三方依赖）。
 * 对应 MCDR 版 requests.Session。
 */
public final class ApiClient {

    private final HttpClient client;

    public ApiClient() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public HttpResponse<String> get(String url, Map<String, String> headers, int timeoutSec)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .GET();
        if (headers != null) {
            headers.forEach(b::header);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 二进制下载（封禁数据库文件） */
    public HttpResponse<byte[]> getBytes(String url, Map<String, String> headers, int timeoutSec)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .GET();
        if (headers != null) {
            headers.forEach(b::header);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    public HttpResponse<String> postJson(String url, Map<String, String> headers, JsonObject body, int timeoutSec)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body.toString()));
        if (headers != null) {
            headers.forEach(b::header);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    public JsonObject parse(String body) {
        try {
            return JsonParser.parseString(body == null ? "{}" : body).getAsJsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }
}
