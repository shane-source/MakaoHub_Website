package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.typesafe.config.Config;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import play.libs.ws.WSClient;
import play.libs.ws.WSResponse;

@Singleton
public class MakaoApi {

    private final WSClient ws;
    private final String baseUrl;

    @Inject
    public MakaoApi(WSClient ws, Config config) {
        this.ws = ws;
        String url = config.getString("makao.api.baseUrl");
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public CompletionStage<ApiResult> post(String path, JsonNode body) {
        return ws.url(baseUrl + path)
                .addHeader("ngrok-skip-browser-warning", "true")
                .setRequestTimeout(Duration.ofSeconds(15))
                .post(body)
                .thenApply(response -> new ApiResult(response.getStatus(), parse(response)))
                .exceptionally(error -> new ApiResult(0, null));
    }

    private JsonNode parse(WSResponse response) {
        try {
            return response.asJson();
        } catch (Exception e) {
            return null;
        }
    }
}