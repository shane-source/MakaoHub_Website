package services;

import com.fasterxml.jackson.databind.JsonNode;

public class ApiResult {

    private final int status;
    private final JsonNode json;

    public ApiResult(int status, JsonNode json) {
        this.status = status;
        this.json = json;
    }

    public int status() {
        return status;
    }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    // Reads a text field from the backend's JSON answer, or "" if it isn't there.
    public String text(String field) {
        return json == null ? "" : json.path(field).asText("");
    }

    // The backend's machine-readable problem code, or "" if there is none.
    public String code() {
        return text("code");
    }

    // A message that is safe to show to the person.
    public String errorMessage() {
        if (status == 0) {
            return "We couldn't reach the server. Please try again in a moment.";
        }
        if (json != null) {
            for (String key : new String[]{"message", "detail"}) {
                JsonNode node = json.get(key);
                if (node != null && node.isTextual() && !node.asText().isBlank()) {
                    return node.asText();
                }
            }
        }
        if (status >= 500) {
            return "Something went wrong on our side. Please try again.";
        }
        return "We couldn't complete that. Please check your details and try again.";
    }
}