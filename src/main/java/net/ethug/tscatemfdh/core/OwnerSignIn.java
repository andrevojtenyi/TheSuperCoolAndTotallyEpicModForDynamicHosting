package net.ethug.tscatemfdh.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * One-time Google sign-in for the world owner (installed-app loopback flow with PKCE).
 * Requests only drive.file, so the resulting token can touch only files this mod creates.
 * The owner supplies their own OAuth "Desktop app" client id/secret (see README).
 */
public final class OwnerSignIn {
    private OwnerSignIn() {}

    public static String signIn(String clientId, String clientSecret, java.util.function.Consumer<String> openUrl) throws Exception {
        byte[] v = Crypto.randomBytes(32);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(v);
        String challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(Crypto.randomBytes(16));

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int port = server.getAddress().getPort();
        String redirect = "http://127.0.0.1:" + port + "/";
        CompletableFuture<String> code = new CompletableFuture<>();
        server.createContext("/", ex -> {
            String q = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
            String c = null, s = null;
            for (String kv : q.split("&")) {
                if (kv.startsWith("code=")) c = java.net.URLDecoder.decode(kv.substring(5), StandardCharsets.UTF_8);
                if (kv.startsWith("state=")) s = java.net.URLDecoder.decode(kv.substring(6), StandardCharsets.UTF_8);
            }
            byte[] msg = "You can close this tab and return to Minecraft.".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, msg.length);
            ex.getResponseBody().write(msg);
            ex.close();
            if (c != null && state.equals(s)) code.complete(c);
        });
        server.start();
        try {
            openUrl.accept("https://accounts.google.com/o/oauth2/v2/auth?" + DriveClient.form(
                    "client_id", clientId, "redirect_uri", redirect, "response_type", "code",
                    "scope", "https://www.googleapis.com/auth/drive.file", "access_type", "offline",
                    "prompt", "consent", "state", state, "code_challenge", challenge, "code_challenge_method", "S256"));
            String authCode = code.get(5, TimeUnit.MINUTES);
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(DriveClient.form(
                            "client_id", clientId, "client_secret", clientSecret, "code", authCode,
                            "code_verifier", verifier, "grant_type", "authorization_code", "redirect_uri", redirect))).build();
            HttpResponse<String> r = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            if (!o.has("refresh_token")) throw new IllegalStateException("Google did not return a refresh token: " + r.body());
            return o.get("refresh_token").getAsString();
        } finally {
            server.stop(0);
        }
    }

    public static final String WORLDS_FOLDER = "Minecraft Shared Worlds";

    /** Creates the world's Drive folder inside the single "Minecraft Shared Worlds" folder and returns its id. */
    public static String createFolder(InviteCode partial, String folderName) throws Exception {
        DriveClient d = new DriveClient(partial);
        return d.createFolder(folderName, d.findOrCreateWorldsFolder(WORLDS_FOLDER));
    }
}
