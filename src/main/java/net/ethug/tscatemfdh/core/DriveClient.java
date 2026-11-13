package net.ethug.tscatemfdh.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Minimal Google Drive v3 REST client, drive.file scope, files live in one folder. */
public final class DriveClient {
    public record DriveFile(String id, String name, Instant created, Instant modified) {}

    private static final String API = "https://www.googleapis.com/drive/v3";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3";
    /**
     * HTTP/1.1 only: each request gets its own connection instead of several big uploads sharing one multiplexed TLS
     * connection, which is where "bad_record_mac" failures on some networks/antivirus setups come from. Rebuilt after a
     * connection-level error so the next attempt starts from a clean connection.
     */
    private static volatile HttpClient http = newClient();

    private static HttpClient newClient() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(15)).build();
    }

    private static synchronized void resetClient() {
        http = newClient();
    }

    private final InviteCode creds;
    private String accessToken;
    private long tokenExpiresAt;

    public DriveClient(InviteCode creds) {
        this.creds = creds;
    }

    /** True when Google says the saved sign-in is no longer valid (expired after 7 days in testing mode, or revoked). */
    public static boolean isAuthExpired(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null && c.getMessage().contains("invalid_grant")) return true;
        }
        return false;
    }

    public String folderId() {
        return creds.folderId();
    }

    private synchronized String token() throws IOException {
        if (accessToken != null && System.currentTimeMillis() < tokenExpiresAt - 60_000) return accessToken;
        String body = form("client_id", creds.clientId(), "client_secret", creds.clientSecret(),
                "refresh_token", creds.refreshToken(), "grant_type", "refresh_token");
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        JsonObject o = JsonParser.parseString(send(req, HttpResponse.BodyHandlers.ofString(), false).body()).getAsJsonObject();
        accessToken = o.get("access_token").getAsString();
        tokenExpiresAt = System.currentTimeMillis() + o.get("expires_in").getAsLong() * 1000;
        return accessToken;
    }

    static String form(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append('&');
            sb.append(URLEncoder.encode(kv[i], StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(kv[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> h, boolean ok404) throws IOException {
        return send(req, h, ok404, 5);
    }

    /**
     * Sends a request, retrying server errors and also connection-level failures (dropped or garbled TLS connections,
     * resets, timeouts) with a fresh connection each time. Real Google errors (4xx) are not retried.
     */
    private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> h, boolean ok404, int attempts) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            HttpResponse<T> r;
            try {
                r = http.send(req, h);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            } catch (IOException e) { // includes SSLException (bad_record_mac), connection reset, timeouts
                last = e;
                resetClient();
                sleepBackoff(attempt);
                continue;
            }
            int c = r.statusCode();
            if (c / 100 == 2 || (ok404 && c == 404)) return r;
            if (c == 429 || c >= 500) {
                last = new IOException("Drive HTTP " + c);
                sleepBackoff(attempt);
                continue;
            }
            throw new IOException("Drive HTTP " + c + ": " + (r.body() instanceof String s ? s : ""));
        }
        throw last;
    }

    private static void sleepBackoff(int attempt) throws IOException {
        try {
            Thread.sleep(Math.min(8000L, 1000L << attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private HttpRequest.Builder auth(String url) throws IOException {
        return HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token());
    }

    public List<DriveFile> list() throws IOException {
        List<DriveFile> out = new ArrayList<>();
        String page = null;
        do {
            String q = "'" + creds.folderId() + "' in parents and trashed=false";
            String url = API + "/files?pageSize=200&fields=" + URLEncoder.encode("nextPageToken,files(id,name,createdTime,modifiedTime)", StandardCharsets.UTF_8)
                    + "&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8) + (page != null ? "&pageToken=" + page : "");
            JsonObject o = JsonParser.parseString(send(auth(url).GET().build(), HttpResponse.BodyHandlers.ofString(), false).body()).getAsJsonObject();
            JsonArray arr = o.getAsJsonArray("files");
            for (JsonElement e : arr) {
                JsonObject f = e.getAsJsonObject();
                out.add(new DriveFile(f.get("id").getAsString(), f.get("name").getAsString(),
                        Instant.parse(f.get("createdTime").getAsString()), Instant.parse(f.get("modifiedTime").getAsString())));
            }
            page = o.has("nextPageToken") ? o.get("nextPageToken").getAsString() : null;
        } while (page != null);
        return out;
    }

    /** Newest file with this name (if duplicates ever exist, the most recently modified one wins). */
    public DriveFile find(String name) throws IOException {
        DriveFile best = null;
        for (DriveFile f : list()) {
            if (f.name().equals(name) && (best == null || f.modified().isAfter(best.modified()))) best = f;
        }
        return best;
    }

    public List<DriveFile> findAll(String name) throws IOException {
        return list().stream().filter(f -> f.name().equals(name)).toList();
    }

    /** Creates a new file in the folder with the given content (resumable upload; any size). */
    public DriveFile create(String name, Path content) throws IOException {
        JsonObject meta = new JsonObject();
        meta.addProperty("name", name);
        JsonArray parents = new JsonArray();
        parents.add(creds.folderId());
        meta.add("parents", parents);
        return resumable("POST", UPLOAD + "/files?uploadType=resumable&fields=id,name,createdTime,modifiedTime", meta.toString(), content);
    }

    public DriveFile update(String id, Path content) throws IOException {
        return resumable("PATCH", UPLOAD + "/files/" + id + "?uploadType=resumable&fields=id,name,createdTime,modifiedTime", "{}", content);
    }

    public DriveFile createBytes(String name, byte[] data) throws IOException {
        Path t = Files.createTempFile("tsc", ".bin");
        try {
            Files.write(t, data);
            return create(name, t);
        } finally {
            Temp.delete(t);
        }
    }

    public DriveFile updateBytes(String id, byte[] data) throws IOException {
        Path t = Files.createTempFile("tsc", ".bin");
        try {
            Files.write(t, data);
            return update(id, t);
        } finally {
            Temp.delete(t);
        }
    }

    private static final int CHUNK = 8 * 1024 * 1024; // multiple of 256 KiB, as the resumable protocol requires

    private DriveFile resumable(String method, String url, String metaJson, Path content) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                HttpRequest init = auth(url).header("Content-Type", "application/json; charset=UTF-8")
                        .method(method, HttpRequest.BodyPublishers.ofString(metaJson)).build();
                String session = send(init, HttpResponse.BodyHandlers.ofString(), false).headers().firstValue("Location")
                        .orElseThrow(() -> new IOException("No upload session"));
                return uploadChunked(session, content);
            } catch (IOException e) {
                String m = String.valueOf(e.getMessage());
                if (m.startsWith("Drive HTTP 4") && !m.startsWith("Drive HTTP 429")) throw e; // a real refusal, not a glitch
                if (m.contains("invalid_grant")) throw e;
                last = e; // e.g. the session expired or the connection kept failing: start over with a new session
                resetClient();
                sleepBackoff(attempt);
            }
        }
        throw last;
    }

    private static DriveFile parseFile(String body) {
        JsonObject f = JsonParser.parseString(body).getAsJsonObject();
        return new DriveFile(f.get("id").getAsString(), f.get("name").getAsString(),
                Instant.parse(f.get("createdTime").getAsString()), Instant.parse(f.get("modifiedTime").getAsString()));
    }

    /**
     * Uploads in 8 MiB pieces. If a piece fails (dropped connection, no reply, server error) it asks Drive how much it has
     * actually stored and carries on from there, so a flaky connection costs a few megabytes, not the whole upload.
     */
    static DriveFile uploadChunked(String session, Path content) throws IOException {
        long total = Files.size(content);
        long offset = 0;
        int failures = 0;
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(content, java.nio.file.StandardOpenOption.READ)) {
            while (true) {
                long end = Math.min(total, offset + CHUNK);
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate((int) (end - offset));
                while (bb.hasRemaining()) {
                    if (ch.read(bb, offset + bb.position()) < 0) throw new IOException("File shrank during upload");
                }
                HttpRequest req = HttpRequest.newBuilder(URI.create(session)).timeout(Duration.ofMinutes(2))
                        .header("Content-Range", total == 0 ? "bytes */0" : "bytes " + offset + "-" + (end - 1) + "/" + total)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(bb.array())).build();
                HttpResponse<String> r;
                try {
                    r = http.send(req, HttpResponse.BodyHandlers.ofString());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                } catch (IOException e) { // connection dropped, garbled TLS, no reply, timeout
                    if (++failures > 10) throw e;
                    resetClient();
                    sleepBackoff(failures - 1);
                    HttpResponse<String> st = queryUpload(session, total);
                    if (st.statusCode() == 200 || st.statusCode() == 201) return parseFile(st.body());
                    offset = persisted(st);
                    continue;
                }
                int c = r.statusCode();
                if (c == 200 || c == 201) return parseFile(r.body());
                if (c == 308) { // piece stored; Drive says how far it has got
                    offset = persisted(r);
                    failures = 0;
                    continue;
                }
                if (c == 404 || c == 410) throw new IOException("Upload session expired (HTTP " + c + ")");
                if (c == 429 || c >= 500) {
                    if (++failures > 10) throw new IOException("Drive HTTP " + c);
                    sleepBackoff(failures - 1);
                    HttpResponse<String> st = queryUpload(session, total);
                    if (st.statusCode() == 200 || st.statusCode() == 201) return parseFile(st.body());
                    offset = persisted(st);
                    continue;
                }
                throw new IOException("Drive HTTP " + c + ": " + r.body());
            }
        }
    }

    /** Asks the upload session how many bytes it has stored. */
    private static HttpResponse<String> queryUpload(String session, long total) throws IOException {
        for (int i = 0; ; i++) {
            try {
                return http.send(HttpRequest.newBuilder(URI.create(session)).timeout(Duration.ofSeconds(60))
                        .header("Content-Range", "bytes */" + total).PUT(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            } catch (IOException e) {
                if (i >= 5) throw e;
                resetClient();
                sleepBackoff(i);
            }
        }
    }

    /** Next byte to send, from a 308 response's "Range: bytes=0-N" (nothing stored yet when it is absent). */
    private static long persisted(HttpResponse<String> r) {
        return r.headers().firstValue("Range").map(v -> Long.parseLong(v.substring(v.indexOf('-') + 1).trim()) + 1).orElse(0L);
    }

    interface TokenSource {
        String get() throws IOException;
    }

    public void download(String id, Path dest) throws IOException {
        downloadResumable(API + "/files/" + id + "?alt=media", this::token, dest);
    }

    /** Downloads to a file; if the connection breaks partway it carries on from the bytes already written. */
    static void downloadResumable(String url, TokenSource bearer, Path dest) throws IOException {
        long[] pos = {0};
        int failures = 0;
        Files.write(dest, new byte[0]);
        while (true) {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                        .header("Authorization", "Bearer " + bearer.get()).GET();
                if (pos[0] > 0) b.header("Range", "bytes=" + pos[0] + "-");
                HttpResponse<java.io.InputStream> r = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
                int c = r.statusCode();
                if (c == 416 && pos[0] > 0) { // asked past the end: everything already arrived
                    r.body().close();
                    return;
                }
                if (c != 200 && c != 206) {
                    String body = new String(r.body().readAllBytes(), StandardCharsets.UTF_8);
                    if (c == 429 || c >= 500) throw new IOException("Drive HTTP " + c);
                    throw new NoRetry("Drive HTTP " + c + ": " + body);
                }
                if (c == 200) pos[0] = 0; // the server sent the whole file, whatever we asked for
                try (java.io.InputStream in = r.body();
                     java.io.OutputStream out = Files.newOutputStream(dest, java.nio.file.StandardOpenOption.CREATE,
                             java.nio.file.StandardOpenOption.WRITE,
                             pos[0] == 0 ? java.nio.file.StandardOpenOption.TRUNCATE_EXISTING : java.nio.file.StandardOpenOption.APPEND)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        pos[0] += n;
                    }
                }
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            } catch (NoRetry e) {
                throw new IOException(e.getMessage());
            } catch (IOException e) {
                String m = String.valueOf(e.getMessage());
                if (m.contains("invalid_grant")) throw e;
                if (++failures > 10) throw e;
                resetClient();
                sleepBackoff(failures - 1);
            }
        }
    }

    private static final class NoRetry extends IOException {
        NoRetry(String m) {
            super(m);
        }
    }

    public byte[] downloadBytes(String id) throws IOException {
        return send(auth(API + "/files/" + id + "?alt=media").GET().build(), HttpResponse.BodyHandlers.ofByteArray(), false).body();
    }

    public void delete(String id) throws IOException {
        send(auth(API + "/files/" + id).DELETE().build(), HttpResponse.BodyHandlers.ofString(), true);
    }

    public void rename(String id, String newName) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("name", newName);
        send(auth(API + "/files/" + id).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(o.toString())).build(), HttpResponse.BodyHandlers.ofString(), false);
    }

    /** Server-side copy within the folder (used to keep the previous snapshot). */
    public void copy(String id, String newName) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("name", newName);
        JsonArray p = new JsonArray();
        p.add(creds.folderId());
        o.add("parents", p);
        send(auth(API + "/files/" + id + "/copy").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(o.toString())).build(), HttpResponse.BodyHandlers.ofString(), false);
    }

    /**
     * Modified time of one file read directly by id. Unlike a folder listing this is not subject to index lag, so it is what
     * decides whether a lease that merely looks stale really is. Null when the file no longer exists.
     */
    public Instant getModified(String id) throws IOException {
        HttpResponse<String> r = send(auth(API + "/files/" + id + "?fields=modifiedTime").GET().build(),
                HttpResponse.BodyHandlers.ofString(), true);
        if (r.statusCode() == 404) return null;
        return Instant.parse(JsonParser.parseString(r.body()).getAsJsonObject().get("modifiedTime").getAsString());
    }

    /** Creates a folder (top level when parentId is null). */
    public String createFolder(String name) throws IOException {
        return createFolder(name, null);
    }

    public String createFolder(String name, String parentId) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("mimeType", FOLDER_MIME);
        if (parentId != null) {
            JsonArray p = new JsonArray();
            p.add(parentId);
            o.add("parents", p);
        }
        HttpResponse<String> r = send(auth(API + "/files?fields=id").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(o.toString())).build(), HttpResponse.BodyHandlers.ofString(), false);
        return JsonParser.parseString(r.body()).getAsJsonObject().get("id").getAsString();
    }

    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";

    /**
     * The single top-level folder that holds every shared world. With the drive.file scope this app only ever sees
     * what it created itself, so a search by name finds our own folder (the oldest one if there are duplicates).
     */
    public String findOrCreateWorldsFolder(String name) throws IOException {
        String q = "name='" + name.replace("'", "\\'") + "' and mimeType='" + FOLDER_MIME + "' and trashed=false";
        String url = API + "/files?pageSize=50&orderBy=createdTime&fields="
                + URLEncoder.encode("files(id,name)", StandardCharsets.UTF_8) + "&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
        JsonArray arr = JsonParser.parseString(send(auth(url).GET().build(), HttpResponse.BodyHandlers.ofString(), false).body())
                .getAsJsonObject().getAsJsonArray("files");
        if (arr != null && !arr.isEmpty()) return arr.get(0).getAsJsonObject().get("id").getAsString();
        return createFolder(name, null);
    }

    /** Moves a folder under newParentId (no-op if it is already there). Returns true if it actually moved. */
    public boolean moveToFolder(String id, String newParentId) throws IOException {
        JsonObject f = JsonParser.parseString(send(auth(API + "/files/" + id + "?fields=parents").GET().build(),
                HttpResponse.BodyHandlers.ofString(), false).body()).getAsJsonObject();
        List<String> old = new ArrayList<>();
        if (f.has("parents")) for (JsonElement e : f.getAsJsonArray("parents")) old.add(e.getAsString());
        if (old.contains(newParentId) && old.size() == 1) return false;
        StringBuilder url = new StringBuilder(API + "/files/" + id + "?fields=id&addParents=" + newParentId);
        old.remove(newParentId);
        if (!old.isEmpty()) url.append("&removeParents=").append(String.join(",", old));
        send(auth(url.toString()).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString(), false);
        return true;
    }

    /** Drive's own clock, so lease expiry does not depend on players' system clocks. */
    public Instant serverNow() throws IOException {
        HttpResponse<String> r = send(auth(API + "/about?fields=kind").GET().build(), HttpResponse.BodyHandlers.ofString(), false);
        return r.headers().firstValue("Date")
                .map(d -> java.time.ZonedDateTime.parse(d, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
                .orElse(Instant.now());
    }
}
