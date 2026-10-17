package org.com.sharekhan.dhan;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

@Service
public class DhanOrderClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    public record Response(int code, String body) {
        @Override public String toString() { return "DhanResponse[http=" + code + ", redacted]"; }
        public JSONObject json() { return new JSONObject(body); }
        public boolean ok() { return code >= 200 && code < 300; }
    }
    /** No automatic retries, redirects, token refresh or response/credential logging. */
    public Response exchange(String method, String path, String token, JSONObject body) throws Exception {
        if (!path.matches("/orders(?:/(?:external/)?[A-Za-z0-9_-]+)?")) throw new IllegalArgumentException("Invalid Dhan order path");
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString());
        var request = HttpRequest.newBuilder(URI.create("https://api.dhan.co/v2" + path)).timeout(Duration.ofSeconds(30))
            .header("access-token", token).header("Content-Type","application/json").header("Accept","application/json")
            .method(method, publisher).build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Dhan request interrupted"); }
    }
}
