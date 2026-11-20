package services;

import com.typesafe.config.Config;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import play.libs.ws.WSRequest;
import play.mvc.Http;

/**
 * Tells the backend which visitor a call is for. Every browser reaches the backend through this website,
 * so without this the backend's rate limits would count all visitors as one.
 */
@Singleton
public class ClientIdentity {

    private final String proxyKey;
    private final boolean trustForwardedFor;

    @Inject
    public ClientIdentity(Config config) {
        this.proxyKey = config.hasPath("makao.api.proxyKey") ? config.getString("makao.api.proxyKey").trim() : "";
        this.trustForwardedFor = config.hasPath("makao.web.trustForwardedFor")
                && config.getBoolean("makao.web.trustForwardedFor");
    }

    /**
     * The visitor's address. When the site runs behind one trusted proxy (a host's load balancer), that proxy
     * appends the visitor's address last, so the last entry is the one that cannot be forged.
     */
    public String ipOf(Http.RequestHeader request) {
        if (request == null) return null;
        if (trustForwardedFor) {
            String chain = request.header("X-Forwarded-For").orElse("");
            if (!chain.isBlank()) {
                String[] parts = chain.split(",");
                String last = parts[parts.length - 1].trim();
                if (valid(last)) return last;
            }
        }
        String direct = request.remoteAddress();
        return valid(direct) ? direct : null;
    }

    /** Adds the visitor's address and the shared key to a backend call. A no-op when either is missing. */
    public WSRequest tag(WSRequest call, String ip) {
        if (proxyKey.isEmpty() || ip == null) return call;
        return call.addHeader("X-Makao-Client-Ip", ip).addHeader("X-Makao-Web-Key", proxyKey);
    }

    private static boolean valid(String ip) {
        return ip != null && ip.length() <= 45 && ip.matches("[0-9a-fA-F:.]+");
    }
}
