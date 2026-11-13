package net.ethug.tscatemfdh.core;

import com.google.gson.Gson;

import javax.crypto.SecretKey;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Incremental world storage. A world is a manifest (path, size, modified time, hash and blob of every file) plus
 * "pack" blobs: encrypted zips holding the files that changed in one upload. Saving uploads only changed files, and the
 * manifest switch is a single small write, so a failed upload never damages the previous version. The previous manifest
 * and its packs are kept for one generation as a rollback point.
 */
public final class WorldStore {
    public static final String MANIFEST = "manifest.json", MANIFEST_PREV = "manifest-prev.json", PACK_PREFIX = "p-";
    private static final long PACK_RAW_BYTES = 256L << 20;     // raw data per pack; compresses to roughly a tenth
    private static final long COMPACT_MIN_BYTES = 64L << 20;   // don't bother compacting small worlds
    private static final int PARALLEL = 2; // gentler on flaky or inspected connections
    private static final Gson GSON = new Gson();

    public static final class Entry {
        public String p;   // path inside the world, '/' separated
        public long s;     // size
        public long m;     // last modified (ms)
        public String h;   // sha-256 of the raw file
        public String b;   // remote id of the pack holding it
        public long c;     // compressed size in the pack
    }

    public static final class Manifest {
        public int gen;
        public List<Entry> files = new ArrayList<>();
        public Map<String, Long> blobs = new HashMap<>(); // pack id -> stored bytes
    }

    public record Result(int filesUploaded, long bytesUploaded, int packs, boolean full, Instant modified) {}

    /** How a local copy was brought up to date: files already matching, files fetched, stray files removed. */
    public record SyncStats(int reused, int fetched, int removed) {}

    private final Remote remote;
    private final SecretKey key;

    public WorldStore(Remote remote, SecretKey key) {
        this.remote = remote;
        this.key = key;
    }

    // ---------- manifest ----------

    private static Remote.File newest(List<Remote.File> files, String name) {
        Remote.File best = null;
        for (Remote.File f : files) {
            if (f.name().equals(name) && (best == null || f.modified().isAfter(best.modified()))) best = f;
        }
        return best;
    }

    /** When the stored world last changed (the manifest's modified time), or null if there is no incremental world. */
    public Instant modified() throws IOException {
        Remote.File f = newest(remote.list(), MANIFEST);
        return f == null ? null : f.modified();
    }

    public boolean exists() throws IOException {
        return newest(remote.list(), MANIFEST) != null;
    }

    private Manifest parse(byte[] sealed) throws Exception {
        return GSON.fromJson(new String(Crypto.open(key, sealed), StandardCharsets.UTF_8), Manifest.class);
    }

    private byte[] seal(Manifest m) throws Exception {
        return Crypto.seal(key, GSON.toJson(m).getBytes(StandardCharsets.UTF_8));
    }

    private void put(List<Remote.File> existing, String name, byte[] data) throws IOException {
        Path t = Files.createTempFile("tsc-man", ".bin");
        try {
            Files.write(t, data);
            Remote.File f = newest(existing, name);
            if (f == null) remote.create(name, t);
            else remote.update(f.id(), t);
        } finally {
            Temp.delete(t);
        }
    }

    // ---------- upload ----------

    private record Cand(String rel, Path path, long size, long mtime, String hash) {}

    private static String sha256(Path f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static boolean needsCompaction(Manifest prev) {
        long stored = 0, live = 0;
        Set<String> used = new HashSet<>();
        for (Entry e : prev.files) {
            live += e.c;
            used.add(e.b);
        }
        for (String id : used) stored += prev.blobs.getOrDefault(id, 0L);
        return stored > COMPACT_MIN_BYTES && stored > 3 * Math.max(live, 1);
    }

    /** Uploads {@code dir} as the new current version, sending only files that changed. */
    public Result upload(Path dir) throws Exception {
        List<Remote.File> listing = remote.list();
        Remote.File curManifest = newest(listing, MANIFEST);
        byte[] prevRaw = curManifest == null ? null : remote.read(curManifest.id());
        Manifest prev = prevRaw == null ? null : parse(prevRaw);
        boolean full = prev == null || needsCompaction(prev);
        Map<String, Entry> prevMap = new HashMap<>();
        if (!full) for (Entry e : prev.files) prevMap.put(e.p, e);

        List<Entry> keep = new ArrayList<>();
        List<Cand> toUpload = new ArrayList<>();
        try (var walk = Files.walk(dir)) {
            for (Path f : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(f) || f.getFileName().toString().equals("session.lock")) continue;
                var at = Files.readAttributes(f, java.nio.file.attribute.BasicFileAttributes.class);
                String rel = dir.relativize(f).toString().replace('\\', '/');
                long size = at.size(), mt = at.lastModifiedTime().toMillis();
                Entry pe = prevMap.get(rel);
                if (pe != null && pe.s == size && pe.m == mt) {
                    keep.add(pe);
                    continue;
                }
                String h = sha256(f);
                if (pe != null && pe.h.equals(h)) { // rewritten with identical content: reuse the stored copy
                    Entry ne = copy(pe);
                    ne.s = size;
                    ne.m = mt;
                    keep.add(ne);
                } else {
                    toUpload.add(new Cand(rel, f, size, mt, h));
                }
            }
        }

        // group changed files into packs and upload them (in parallel); nothing references them until the manifest is switched
        List<List<Cand>> groups = new ArrayList<>();
        List<Cand> cur = new ArrayList<>();
        long curRaw = 0;
        for (Cand c : toUpload) {
            if (!cur.isEmpty() && curRaw + c.size() > PACK_RAW_BYTES) {
                groups.add(cur);
                cur = new ArrayList<>();
                curRaw = 0;
            }
            cur.add(c);
            curRaw += c.size();
        }
        if (!cur.isEmpty()) groups.add(cur);

        Manifest next = new Manifest();
        next.gen = prev == null ? 1 : prev.gen + 1;
        next.files.addAll(keep);
        long uploaded = 0;
        if (!groups.isEmpty()) {
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(PARALLEL, groups.size()));
            try {
                List<Future<Object[]>> futures = new ArrayList<>();
                for (List<Cand> g : groups) futures.add(pool.submit(() -> uploadPack(g)));
                for (Future<Object[]> fu : futures) {
                    Object[] r = fu.get();
                    @SuppressWarnings("unchecked") List<Entry> entries = (List<Entry>) r[0];
                    next.files.addAll(entries);
                    next.blobs.put((String) r[1], (Long) r[2]);
                    uploaded += (Long) r[2];
                }
            } catch (ExecutionException e) {
                throw e.getCause() instanceof Exception ex ? ex : e;
            } finally {
                pool.shutdownNow();
            }
        }
        if (prev != null) for (Entry e : keep) {
            Long sz = prev.blobs.get(e.b);
            if (sz != null) next.blobs.putIfAbsent(e.b, sz);
        }

        // switch: the current manifest becomes the rollback copy, then the new one is written
        if (prevRaw != null) put(listing, MANIFEST_PREV, prevRaw);
        Path t = Files.createTempFile("tsc-man", ".bin");
        Remote.File written;
        try {
            Files.write(t, seal(next));
            written = curManifest == null ? remote.create(MANIFEST, t) : remote.update(curManifest.id(), t);
        } finally {
            Temp.delete(t);
        }

        // garbage-collect packs referenced by neither this version nor the rollback version
        Set<String> referenced = new HashSet<>();
        for (Entry e : next.files) referenced.add(e.b);
        if (prev != null) for (Entry e : prev.files) referenced.add(e.b);
        for (Remote.File f : listing) {
            if (f.name().startsWith(PACK_PREFIX) && !referenced.contains(f.id())) {
                try {
                    remote.delete(f.id());
                } catch (IOException ignored) { // collected next time
                }
            }
        }
        return new Result(toUpload.size(), uploaded, groups.size(), full, written.modified());
    }

    private static Entry copy(Entry e) {
        Entry n = new Entry();
        n.p = e.p;
        n.s = e.s;
        n.m = e.m;
        n.h = e.h;
        n.b = e.b;
        n.c = e.c;
        return n;
    }

    /** Returns {entries, packId, storedBytes}. */
    private Object[] uploadPack(List<Cand> group) throws Exception {
        Path zip = Files.createTempFile("tsc-pack", ".zip");
        Path enc = Files.createTempFile("tsc-pack", ".enc");
        try {
            List<Entry> entries = new ArrayList<>();
            try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zip), 1 << 16))) {
                z.setLevel(java.util.zip.Deflater.BEST_SPEED);
                for (Cand c : group) {
                    ZipEntry ze = new ZipEntry(c.rel());
                    z.putNextEntry(ze);
                    Files.copy(c.path(), z);
                    z.closeEntry();
                    Entry e = new Entry();
                    e.p = c.rel();
                    e.s = c.size();
                    e.m = c.mtime();
                    e.h = c.hash();
                    e.c = ze.getCompressedSize();
                    entries.add(e);
                }
            }
            Crypto.encryptFile(key, zip, enc);
            long stored = Files.size(enc);
            Remote.File f = remote.create(PACK_PREFIX + HexFormat.of().formatHex(Crypto.randomBytes(8)), enc);
            for (Entry e : entries) e.b = f.id();
            return new Object[]{entries, f.id(), stored};
        } finally {
            Temp.delete(zip);
            Temp.delete(enc);
        }
    }

    // ---------- download ----------

    /** Downloads the current version into {@code dest} (replacing it). Returns false if nothing is stored yet. */
    public boolean download(Path dest) throws Exception {
        List<Remote.File> listing = remote.list();
        Remote.File cm = newest(listing, MANIFEST);
        if (cm == null) return false;
        Manifest m = parse(remote.read(cm.id()));
        Map<String, Entry> byPath = new HashMap<>();
        Map<String, List<Entry>> byBlob = new LinkedHashMap<>();
        for (Entry e : m.files) {
            byPath.put(e.p, e);
            byBlob.computeIfAbsent(e.b, k -> new ArrayList<>()).add(e);
        }
        Path staging = dest.resolveSibling(dest.getFileName() + ".incoming");
        WorldSync.deleteTree(staging);
        Files.createDirectories(staging);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(PARALLEL, Math.max(1, byBlob.size())));
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (var blob : byBlob.entrySet()) {
                futures.add(pool.submit(() -> {
                    extractPack(blob.getKey(), blob.getValue(), staging);
                    return null;
                }));
            }
            for (Future<?> f : futures) f.get();
        } catch (ExecutionException e) {
            WorldSync.deleteTree(staging);
            throw e.getCause() instanceof Exception ex ? ex : e;
        } finally {
            pool.shutdownNow();
        }
        for (Entry e : m.files) { // every file in the manifest must have arrived
            if (!Files.isRegularFile(staging.resolve(e.p))) {
                WorldSync.deleteTree(staging);
                throw new IOException("Stored world is incomplete (missing " + e.p + ")");
            }
        }
        WorldSync.deleteTree(dest);
        Files.move(staging, dest);
        return true;
    }

    /**
     * Brings a local copy of the world in line with the current stored version, fetching only files that differ
     * (size or modified time) from what is already in {@code dest}. Returns null if nothing is stored yet.
     * The caller should mark {@code dest} as unreliable while this runs; it is updated in place.
     */
    public SyncStats downloadInto(Path dest) throws Exception {
        List<Remote.File> listing = remote.list();
        Remote.File cm = newest(listing, MANIFEST);
        if (cm == null) return null;
        Manifest m = parse(remote.read(cm.id()));
        Files.createDirectories(dest);
        Path base = dest.toAbsolutePath().normalize();
        Set<String> want = new HashSet<>();
        Map<String, List<Entry>> need = new LinkedHashMap<>();
        int reused = 0, fetched = 0, removed = 0;
        for (Entry e : m.files) {
            want.add(e.p);
            Path f = base.resolve(e.p).normalize();
            if (!f.startsWith(base)) throw new IOException("Unsafe path in stored world");
            boolean ok = false;
            if (Files.isRegularFile(f)) {
                var at = Files.readAttributes(f, java.nio.file.attribute.BasicFileAttributes.class);
                ok = at.size() == e.s && at.lastModifiedTime().toMillis() == e.m;
            }
            if (ok) {
                reused++;
            } else {
                need.computeIfAbsent(e.b, k -> new ArrayList<>()).add(e);
                fetched++;
            }
        }
        try (var walk = Files.walk(base)) {
            for (Path f : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(f) || f.getFileName().toString().equals("session.lock")) continue;
                if (!want.contains(base.relativize(f).toString().replace('\\', '/'))) {
                    Files.delete(f);
                    removed++;
                }
            }
        }
        if (!need.isEmpty()) {
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(PARALLEL, need.size()));
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (var blob : need.entrySet()) {
                    futures.add(pool.submit(() -> {
                        extractPack(blob.getKey(), blob.getValue(), base);
                        return null;
                    }));
                }
                for (Future<?> f : futures) f.get();
            } catch (ExecutionException e) {
                throw e.getCause() instanceof Exception ex ? ex : e;
            } finally {
                pool.shutdownNow();
            }
        }
        for (Entry e : m.files) {
            if (!Files.isRegularFile(base.resolve(e.p))) throw new IOException("Stored world is incomplete (missing " + e.p + ")");
        }
        return new SyncStats(reused, fetched, removed);
    }

    private void extractPack(String blobId, List<Entry> wanted, Path staging) throws Exception {
        Path enc = Files.createTempFile("tsc-dl", ".enc");
        Path zip = Files.createTempFile("tsc-dl", ".zip");
        try {
            remote.download(blobId, enc);
            Crypto.decryptFile(key, enc, zip);
            try (ZipFile zf = new ZipFile(zip.toFile())) {
                for (Entry e : wanted) {
                    ZipEntry ze = zf.getEntry(e.p);
                    if (ze == null) throw new IOException("Pack is missing " + e.p);
                    Path out = staging.resolve(e.p).normalize();
                    if (!out.startsWith(staging)) throw new IOException("Unsafe path in stored world");
                    Files.createDirectories(out.getParent());
                    try (InputStream in = zf.getInputStream(ze)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.setLastModifiedTime(out, java.nio.file.attribute.FileTime.fromMillis(e.m));
                }
            }
        } finally {
            Temp.delete(enc);
            Temp.delete(zip);
        }
    }
}
