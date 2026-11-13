package net.ethug.tscatemfdh.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Zips + encrypts a world folder to/from Drive. Keeps the previous snapshot as world-prev.bin. */
public final class WorldSync {
    private static final String WORLD = "world.bin", PREV = "world-prev.bin", META = "meta.json";
    private static final Gson GSON = new Gson();

    private final DriveClient drive;

    public WorldSync(DriveClient drive) {
        this.drive = drive;
    }

    public boolean exists() throws IOException {
        return drive.find(META) != null;
    }

    private static String b64(byte[] b) {
        return java.util.Base64.getEncoder().encodeToString(b);
    }

    private static byte[] unb64(String s) {
        return java.util.Base64.getDecoder().decode(s);
    }

    private byte[] metaFor(SecretKey masterKey, String passcode) throws Exception {
        byte[] wsalt = Crypto.randomBytes(16);
        SecretKey wrap = Crypto.deriveWrapKey(passcode, wsalt);
        JsonObject o = new JsonObject();
        o.addProperty("wsalt", b64(wsalt));
        o.addProperty("wrapped", b64(Crypto.seal(wrap, masterKey.getEncoded())));
        o.addProperty("check", b64(Crypto.seal(masterKey, "tscatemfdh".getBytes(StandardCharsets.UTF_8))));
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Creates a random master key and wraps it with the passcode (any length, may be empty). All world data is
     * encrypted with the master key, so the passcode can be changed later without touching the world or locking out
     * people who already hold the key.
     */
    public SecretKey initialise(String passcode) throws Exception {
        SecretKey master = new javax.crypto.spec.SecretKeySpec(Crypto.randomBytes(32), "AES");
        drive.createBytes(META, metaFor(master, passcode));
        return master;
    }

    /** New connection: derives the master key from the passcode. Throws SecurityException when wrong. */
    public SecretKey unlock(String passcode) throws Exception {
        DriveClient.DriveFile m = drive.find(META);
        if (m == null) throw new IOException("This shared world has no data yet");
        JsonObject o = GSON.fromJson(new String(drive.downloadBytes(m.id()), StandardCharsets.UTF_8), JsonObject.class);
        try {
            if (o.has("wrapped")) {
                SecretKey wrap = Crypto.deriveWrapKey(passcode, unb64(o.get("wsalt").getAsString()));
                return new javax.crypto.spec.SecretKeySpec(Crypto.open(wrap, unb64(o.get("wrapped").getAsString())), "AES");
            }
            // legacy worlds: key was derived straight from the passcode
            SecretKey key = Crypto.deriveKey(passcode, unb64(o.get("salt").getAsString()));
            Crypto.open(key, unb64(o.get("check").getAsString()));
            return key;
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("Wrong passcode");
        }
    }

    /** Existing connection: confirms a stored master key still matches this world. */
    public void verifyKey(SecretKey key) throws Exception {
        DriveClient.DriveFile m = drive.find(META);
        if (m == null) throw new IOException("This shared world has no data yet");
        JsonObject o = GSON.fromJson(new String(drive.downloadBytes(m.id()), StandardCharsets.UTF_8), JsonObject.class);
        try {
            Crypto.open(key, unb64(o.get("check").getAsString()));
        } catch (Exception e) {
            throw new SecurityException("Stored key no longer matches");
        }
    }

    /** Owner: set a new passcode for FUTURE connections. World data and existing members are unaffected. */
    public void rewrap(SecretKey masterKey, String newPasscode) throws Exception {
        DriveClient.DriveFile meta = drive.find(META);
        if (meta == null) throw new IOException("This shared world has no data yet");
        drive.updateBytes(meta.id(), metaFor(masterKey, newPasscode));
    }

    /** Small encrypted JSON side files (access list, player list). Returns null when absent. */
    public String readJson(String name, SecretKey key) throws Exception {
        DriveClient.DriveFile f = drive.find(name);
        if (f == null) return null;
        return new String(Crypto.open(key, drive.downloadBytes(f.id())), StandardCharsets.UTF_8);
    }

    public void writeJson(String name, SecretKey key, String json) throws Exception {
        byte[] sealed = Crypto.seal(key, json.getBytes(StandardCharsets.UTF_8));
        DriveClient.DriveFile f = drive.find(name);
        if (f == null) drive.createBytes(name, sealed);
        else drive.updateBytes(f.id(), sealed);
    }

    private static final String ICON = "icon.bin";

    /** Uploads the world's icon.png (the one vanilla makes for singleplayer worlds) as a small separate file. */
    public void uploadIcon(Path worldDir, SecretKey key) {
        try {
            Path icon = worldDir.resolve("icon.png");
            if (!Files.isRegularFile(icon)) return;
            byte[] sealed = Crypto.seal(key, Files.readAllBytes(icon));
            DriveClient.DriveFile f = drive.find(ICON);
            if (f == null) drive.createBytes(ICON, sealed);
            else drive.updateBytes(f.id(), sealed);
        } catch (Exception ignored) { // cosmetic only
        }
    }

    /** Drive's modified time of the icon, or null if none. */
    public java.time.Instant iconModified() throws IOException {
        DriveClient.DriveFile f = drive.find(ICON);
        return f == null ? null : f.modified();
    }

    public byte[] downloadIcon(SecretKey key) throws Exception {
        DriveClient.DriveFile f = drive.find(ICON);
        return f == null ? null : Crypto.open(key, drive.downloadBytes(f.id()));
    }

    private WorldStore store(SecretKey key) {
        return new WorldStore(new DriveRemote(drive), key);
    }

    /** Uploads a world folder incrementally (only changed files), then retires the old single-file format if present. */
    public WorldStore.Result uploadTree(Path dir, SecretKey key) throws Exception {
        WorldStore.Result r = store(key).upload(dir);
        migrateLegacy();
        return r;
    }

    public WorldStore.Result upload(Path worldDir, SecretKey key) throws Exception {
        WorldStore.Result r = uploadTree(worldDir, key);
        uploadIcon(worldDir, key);
        return r;
    }

    /** Worlds saved by older versions keep their single world.bin; once a new-format copy exists it is only a backup. */
    private void migrateLegacy() throws IOException {
        DriveClient.DriveFile old = drive.find(WORLD);
        if (old != null) drive.rename(old.id(), "world-v1-backup.bin");
        DriveClient.DriveFile prev = drive.find(PREV);
        if (prev != null) drive.delete(prev.id());
    }

    /** Encrypts and uploads an already-made snapshot zip (does not delete it). */
    public java.time.Instant uploadZip(Path zip, SecretKey key) throws Exception {
        Path enc = Files.createTempFile("tsc-world", ".enc");
        try {
            Crypto.encryptFile(key, zip, enc);
            DriveClient.DriveFile cur = drive.find(WORLD);
            if (cur == null) {
                return drive.create(WORLD, enc).modified();
            } else {
                DriveClient.DriveFile old = drive.find(PREV);
                if (old != null) drive.delete(old.id());
                drive.copy(cur.id(), PREV);
                DriveClient.DriveFile updated = drive.update(cur.id(), enc);
                for (DriveClient.DriveFile dup : drive.findAll(WORLD)) { // never leave two "world.bin" around
                    if (!dup.id().equals(updated.id())) drive.delete(dup.id());
                }
                return updated.modified();
            }
        } finally {
            Temp.delete(enc);
        }
    }

    public java.time.Instant worldModified() throws IOException {
        DriveClient.DriveFile mf = drive.find(WorldStore.MANIFEST);
        if (mf != null) return mf.modified();
        DriveClient.DriveFile f = drive.find(WORLD);
        return f == null ? null : f.modified();
    }

    /** Returns false if nothing has been uploaded yet. */
    public boolean download(Path worldDir, SecretKey key) throws Exception {
        if (store(key).download(worldDir)) return true;
        return downloadLegacy(worldDir, key);
    }

    /**
     * Updates a local copy in place so only differing files are downloaded. Returns {reused, fetched}, or null if
     * nothing has been uploaded. Worlds still in the old single-file format get a full download.
     */
    public int[] downloadInto(Path dest, SecretKey key) throws Exception {
        WorldStore.SyncStats st = store(key).downloadInto(dest);
        if (st != null) return new int[]{st.reused(), st.fetched()};
        return downloadLegacy(dest, key) ? new int[]{0, -1} : null;
    }

    private boolean downloadLegacy(Path worldDir, SecretKey key) throws Exception {
        DriveClient.DriveFile cur = drive.find(WORLD);
        if (cur == null) return false;
        Path enc = Files.createTempFile("tsc-world", ".enc");
        Path zip = Files.createTempFile("tsc-world", ".zip");
        Path staging = worldDir.resolveSibling(worldDir.getFileName() + ".incoming");
        try {
            drive.download(cur.id(), enc);
            Crypto.decryptFile(key, enc, zip); // GCM failure here = corrupt/tampered, old world untouched
            deleteTree(staging);
            unzip(zip, staging);
            deleteTree(worldDir);
            Files.move(staging, worldDir);
            return true;
        } finally {
            Temp.delete(enc);
            Temp.delete(zip);
            deleteTree(staging);
        }
    }

    public static void zip(Path dir, Path zip) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.setLevel(java.util.zip.Deflater.BEST_SPEED); // region files are mostly zero padding: level 1 gives ~10x smaller uploads at a fraction of the CPU of the default
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    if (f.getFileName().toString().equals("session.lock")) return FileVisitResult.CONTINUE;
                    zos.putNextEntry(new ZipEntry(dir.relativize(f).toString().replace('\\', '/')));
                    try {
                        Files.copy(f, zos);
                    } catch (IOException e) { // file being written by the server; skipped entry is retried next sync
                    }
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /**
     * Brings {@code mirror} in line with {@code src}, copying only files whose size or modified time differ (and removing
     * files that no longer exist). Returns how many files were copied or removed. Cheap when little has changed, so it
     * can run on the game thread right after a flush, and the mirror is then a consistent copy to pack off-thread.
     */
    public static int syncMirror(Path src, Path mirror) throws IOException {
        Files.createDirectories(mirror);
        int[] changed = {0};
        java.util.Set<Path> seen = new java.util.HashSet<>();
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                Files.createDirectories(mirror.resolve(src.relativize(d).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                if (f.getFileName().toString().equals("session.lock")) return FileVisitResult.CONTINUE;
                Path rel = src.relativize(f);
                seen.add(rel);
                Path t = mirror.resolve(rel.toString());
                if (!Files.exists(t) || Files.size(t) != a.size()
                        || Files.getLastModifiedTime(t).toMillis() != a.lastModifiedTime().toMillis()) {
                    try {
                        Files.copy(f, t, StandardCopyOption.REPLACE_EXISTING);
                        Files.setLastModifiedTime(t, a.lastModifiedTime()); // stat taken BEFORE the copy, so a mid-copy change is caught next time
                        changed[0]++;
                    } catch (IOException e) {
                        Files.deleteIfExists(t); // unreadable right now; leave it out so it is retried
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        try (var walk = Files.walk(mirror)) {
            for (Path m : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                if (m.equals(mirror)) continue;
                Path rel = mirror.relativize(m);
                if (Files.isDirectory(m)) {
                    if (!Files.exists(src.resolve(rel.toString()))) Files.deleteIfExists(m);
                } else if (!seen.contains(rel)) {
                    Files.deleteIfExists(m);
                    changed[0]++;
                }
            }
        }
        return changed[0];
    }

    public static void unzip(Path zip, Path dest) throws IOException {
        Files.createDirectories(dest);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                Path out = dest.resolve(e.getName()).normalize();
                if (!out.startsWith(dest)) throw new IOException("Unsafe path in archive"); // zip-slip guard
                if (e.isDirectory()) continue;
                Files.createDirectories(out.getParent());
                Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) {
            for (Path x : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                for (int i = 0; ; i++) {
                    try {
                        Files.deleteIfExists(x);
                        break;
                    } catch (IOException e) {
                        if (i >= 5) throw e;
                        try {
                            Thread.sleep(200L * (i + 1));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw e;
                        }
                    }
                }
            }
        }
    }
}
