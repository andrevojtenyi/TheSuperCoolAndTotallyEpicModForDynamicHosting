package net.ethug.tscatemfdh.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Host election on top of Drive. Drive has no atomic compare-and-swap, so each candidate writes its own
 * "lease-&lt;nonce&gt;" file and the live lease with the earliest Drive createdTime wins. A lease is live
 * while its Drive modifiedTime is within EXPIRY of Drive's clock (the holder heartbeats from its own thread).
 * Other candidates' leases are never deleted unless they are long dead.
 */
public final class Lease {
    public static final Duration EXPIRY = Duration.ofSeconds(90);
    public static final Duration HEARTBEAT = Duration.ofSeconds(10);
    private static final Gson GSON = new Gson();

    public record Info(String holder, String address) {}

    private final DriveClient drive;
    private final SecretKey key;
    private final String nonce = java.util.HexFormat.of().formatHex(Crypto.randomBytes(8));
    private final String holder;
    private volatile DriveClient.DriveFile mine;
    private volatile String address = "";

    public Lease(DriveClient drive, SecretKey key, String holderName) {
        this.drive = drive;
        this.key = key;
        this.holder = holderName;
    }

    private byte[] body() throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("holder", holder);
        o.addProperty("address", address);
        return Crypto.seal(key, o.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Returns true when this client is the host. If false, {@link #current()} says who is. */
    public synchronized boolean tryAcquire() throws Exception {
        if (mine == null) mine = drive.createBytes("lease-" + nonce, body());
        else mine = drive.updateBytes(mine.id(), body());
        Thread.sleep(2500); // let concurrent candidates' files become visible in listings
        return winner().map(w -> w.name().equals("lease-" + nonce)).orElse(false);
    }

    /** Human-readable view of every lease file, for logs. */
    public String describe() {
        try {
            Instant now = drive.serverNow();
            StringBuilder sb = new StringBuilder();
            for (DriveClient.DriveFile f : drive.list()) {
                if (!f.name().startsWith("lease-")) continue;
                sb.append(f.name().equals("lease-" + nonce) ? "[me] " : "").append(f.name())
                        .append(" age=").append(Duration.between(f.modified(), now).toSeconds()).append("s; ");
            }
            return sb.length() == 0 ? "(no leases)" : sb.toString();
        } catch (Exception e) {
            return "(could not list: " + e.getMessage() + ")";
        }
    }

    private boolean isMine(DriveClient.DriveFile f) {
        return f.name().equals("lease-" + nonce);
    }

    /**
     * Live leases, earliest created first. A lease that only LOOKS stale in the listing is re-read by id before it is
     * written off, because listings can lag behind heartbeats and a wrongly dismissed host means two hosts.
     */
    private List<DriveClient.DriveFile> live() throws Exception {
        Instant now = drive.serverNow();
        List<DriveClient.DriveFile> out = new java.util.ArrayList<>();
        for (DriveClient.DriveFile f : drive.list()) {
            if (!f.name().startsWith("lease-")) continue;
            if (isMine(f) || Duration.between(f.modified(), now).compareTo(EXPIRY) <= 0) {
                out.add(f);
                continue;
            }
            Instant real = drive.getModified(f.id());
            if (real != null && Duration.between(real, now).compareTo(EXPIRY) <= 0) out.add(f);
        }
        out.sort(Comparator.comparing(DriveClient.DriveFile::created).thenComparing(DriveClient.DriveFile::name));
        return out;
    }

    private Optional<DriveClient.DriveFile> winner() throws Exception {
        return live().stream().findFirst();
    }

    /** True while ours is the earliest live lease. Used to detect (and resolve) two hosts being active at once. */
    public boolean isStillWinner() throws Exception {
        return winner().map(this::isMine).orElse(false);
    }

    /**
     * Called by a freshly elected host: removes leases that have gone silent so a host that was merely asleep or offline
     * cannot come back as the "older" lease and claim the world again.
     */
    public void purgeStale() throws Exception {
        Instant now = drive.serverNow();
        for (DriveClient.DriveFile f : drive.list()) {
            if (!f.name().startsWith("lease-") || isMine(f)) continue;
            if (Duration.between(f.modified(), now).compareTo(EXPIRY) <= 0) continue;
            Instant real = drive.getModified(f.id());
            if (real != null && Duration.between(real, now).compareTo(EXPIRY) > 0) drive.delete(f.id());
        }
    }

    /** The live host, if any (reads its published address). */
    public Optional<Info> current() throws Exception {
        Optional<DriveClient.DriveFile> w = winner();
        if (w.isEmpty() || (mine != null && w.get().id().equals(mine.id()))) return Optional.empty();
        JsonObject o = GSON.fromJson(new String(Crypto.open(key, drive.downloadBytes(w.get().id())), StandardCharsets.UTF_8), JsonObject.class);
        return Optional.of(new Info(o.get("holder").getAsString(), o.get("address").getAsString()));
    }

    public void publishAddress(String addr) throws Exception {
        this.address = addr;
        heartbeat();
    }

    /** Keeps the lease live. If our file vanished (deleted/expired away), a new one is created. */
    public synchronized void heartbeat() throws Exception {
        DriveClient.DriveFile m = mine;
        if (m == null) return;
        try {
            mine = drive.updateBytes(m.id(), body());
        } catch (IOException e) {
            if (String.valueOf(e.getMessage()).contains("404")) mine = drive.createBytes("lease-" + nonce, body());
            else throw e;
        }
    }

    public synchronized void release() {
        try {
            if (mine != null) drive.delete(mine.id());
        } catch (IOException ignored) {
        }
        mine = null;
        address = "";
    }
}
