package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.typesafe.config.Config;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import play.libs.ws.WSClient;
import play.libs.ws.WSResponse;
import play.mvc.Http;

@Singleton
public class MakaoApi {

    private final WSClient ws;
    private final String baseUrl;
    private final ClientIdentity identity;

    @Inject
    public MakaoApi(WSClient ws, Config config, ClientIdentity identity) {
        this.ws = ws;
        this.identity = identity;
        String url = config.getString("makao.api.baseUrl");
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public CompletionStage<ApiResult> post(String path, JsonNode body) {
        return post((Http.RequestHeader) null, path, body);
    }

    /** Same, on behalf of the visitor who made this request, so the backend limits them and not the whole site. */
    public CompletionStage<ApiResult> post(Http.RequestHeader request, String path, JsonNode body) {
        return identity.tag(ws.url(baseUrl + path), identity.ipOf(request))
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