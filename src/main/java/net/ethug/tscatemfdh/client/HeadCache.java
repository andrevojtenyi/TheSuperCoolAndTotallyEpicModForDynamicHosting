package net.ethug.tscatemfdh.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.ethug.tscatemfdh.tscatemfdh;
import net.fabricmc.loader.api.FabricLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 8x8 player face pixels by UUID. Fetched once from Mojang's public skin servers (the same data the game uses),
 * cached on disk for a week, and drawn as pixel quads in the player panel.
 */
public final class HeadCache {
    private static final Map<String, int[]> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
    private static final ExecutorService EX = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tscatemfdh-heads");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final long MAX_AGE_MS = 7L * 24 * 3600 * 1000;

    private HeadCache() {}

    /** ARGB x64 (8x8), or null while still loading. */
    public static int[] get(String uuid) {
        if (uuid == null || uuid.replace("-", "").length() != 32) return null;
        int[] c = CACHE.get(uuid);
        if (c != null) return c;
        if (LOADING.add(uuid)) EX.submit(() -> load(uuid));
        return null;
    }

    private static Path file(String uuid) {
        return FabricLoader.getInstance().getConfigDir().resolve("tscatemfdh-heads").resolve(uuid.replace("-", "") + ".bin");
    }

    private static void load(String uuid) {
        int[] fallback = new int[64];
        java.util.Arrays.fill(fallback, 0xFF707070);
        int[] stale = null;
        try {
            Path f = file(uuid);
            if (Files.exists(f)) {
                ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(f));
                if (b.remaining() == 256) {
                    stale = new int[64];
                    for (int i = 0; i < 64; i++) stale[i] = b.getInt();
                    if (System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis() < MAX_AGE_MS) {
                        CACHE.put(uuid, stale);
                        return;
                    }
                }
            }
            int[] px = fetch(uuid);
            ByteBuffer out = ByteBuffer.allocate(256);
            for (int p : px) out.putInt(p);
            Files.createDirectories(f.getParent());
            Files.write(f, out.array());
            CACHE.put(uuid, px);
        } catch (Exception e) {
            tscatemfdh.LOGGER.debug("Head fetch failed for {}", uuid, e);
            CACHE.put(uuid, stale != null ? stale : fallback);
        } finally {
            LOADING.remove(uuid);
        }
    }

    private static int[] fetch(String uuid) throws Exception {
        String id = uuid.replace("-", "");
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + id))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("profile HTTP " + r.statusCode());
        String tex = null;
        for (var e : JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("properties")) {
            JsonObject o = e.getAsJsonObject();
            if ("textures".equals(o.get("name").getAsString())) tex = o.get("value").getAsString();
        }
        if (tex == null) throw new IllegalStateException("no textures");
        JsonObject t = JsonParser.parseString(new String(Base64.getDecoder().decode(tex), StandardCharsets.UTF_8)).getAsJsonObject();
        String url = t.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString().replace("http://", "https://");
        HttpResponse<byte[]> img = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        BufferedImage skin = ImageIO.read(new ByteArrayInputStream(img.body()));
        int[] px = new int[64];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int base = skin.getRGB(8 + x, 8 + y);          // face
                int hat = skin.getRGB(40 + x, 8 + y);          // hat layer
                px[y * 8 + x] = (hat >>> 24) > 0x80 ? (hat | 0xFF000000) : (base | 0xFF000000);
            }
        }
        return px;
    }
}
