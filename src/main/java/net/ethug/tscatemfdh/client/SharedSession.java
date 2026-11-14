package net.ethug.tscatemfdh.client;

import net.ethug.tscatemfdh.SharedConfig;
import net.ethug.tscatemfdh.core.*;
import net.ethug.tscatemfdh.tscatemfdh;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;

import javax.crypto.SecretKey;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One shared-world session on this client: elect host, then either host (download -> integrated server ->
 * e4mc tunnel -> publish address -> heartbeat + autosave) or join the host. Hands off automatically when the host leaves.
 */
public final class SharedSession {
    public static final SharedSession INSTANCE = new SharedSession();
    private static final Pattern DOMAIN = Pattern.compile("([a-z0-9][a-z0-9.-]*\\.e4mc\\.link)", Pattern.CASE_INSENSITIVE);
    private static final String STATE_FILE = "tscatemfdh-state.json";

    public volatile String status = "";
    private volatile SharedConnectScreen connectScreen;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tscatemfdh-worker");
        t.setDaemon(true);
        return t;
    });
    private ScheduledExecutorService ticker;
    private ScheduledExecutorService heartbeatExec;
    private volatile String lastFailedAddress = "";
    private volatile long lastFailedAt;
    private final AtomicReference<String> domain = new AtomicReference<>();

    private volatile SharedConfig.Entry entry;
    private volatile DriveClient drive;
    private volatile WorldSync sync;
    private volatile SecretKey key;
    private volatile Lease lease;
    private volatile boolean active;
    private volatile boolean hosting;
    private volatile long retryBackoffMs = 5000;
    private volatile Path hostWorldDir;
    private volatile Instant lastBackedUp = Instant.EPOCH;
    private volatile boolean connected;
    private volatile boolean autosaving;
    private volatile Path mirrorDir;
    private volatile String hostLevelId;
    private volatile long sessionSince;
    private volatile long lastSavedAt;
    private volatile boolean markerWritten;
    private volatile String lastFailure = "";
    private volatile int autosaveFailures;
    private final java.util.concurrent.locks.ReentrantLock recoveryLock = new java.util.concurrent.locks.ReentrantLock();
    private volatile boolean hostDirFresh; // hostWorldDir was just prepared this session and the world has not started yet
    private volatile boolean offline;          // host lost connectivity: sharing stopped, host plays on alone
    private volatile boolean heartbeatPaused;
    private volatile int hbFailures;
    private volatile int badTunnelChecks;
    private volatile long acquiredAt;
    private volatile int splitChecks;
    private volatile boolean authExpired;
    private volatile int monitorTicks;
    private volatile IntegratedServer hostServer;
    private volatile java.time.Instant baseModified; // Drive's world.bin time when we last downloaded/uploaded it
    private volatile NewWorldShare newWorldShare;
    private volatile String newWorldFolder;

    private record NewWorldShare(String name, String passcode, String clientId, String clientSecret, Runnable onDone) {}
    private volatile long phaseStartedAt;

    private SharedSession() {}

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> INSTANCE.onChatMessage(msg));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (INSTANCE.newWorldShare != null && INSTANCE.newWorldFolder != null && server instanceof IntegratedServer) {
                INSTANCE.finishNewWorldShare();
                return;
            }
            if (INSTANCE.hosting && server instanceof IntegratedServer) INSTANCE.onHostStopped(server);
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (INSTANCE.newWorldShare != null && client.getSingleplayerServer() != null) INSTANCE.onNewWorldLoaded(client);
            if (INSTANCE.active && !INSTANCE.hosting) {
                INSTANCE.connected = true;
                INSTANCE.connectScreen = null;
                INSTANCE.status = "Connected to " + (INSTANCE.entry != null ? INSTANCE.entry.name : "shared world");
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            INSTANCE.connected = false;
            if (INSTANCE.active && !INSTANCE.hosting) INSTANCE.watchForHostLoss();
        });
    }

    public boolean isActive() {
        return active;
    }

    public boolean isHosting() {
        return hosting;
    }

    /** True while the host is packing/uploading an autosave. */
    public boolean isAutosaving() {
        return autosaving;
    }

    /** Called for every chat line (server messages via the event, e4mc's own via a chat mixin). */
    public void onChatMessage(net.minecraft.network.chat.Component msg) {
        if (!hosting || domain.get() != null) return;
        String d = e4mcDomain(msg);
        if (d != null) {
            domain.compareAndSet(null, d);
            tscatemfdh.LOGGER.info("Captured e4mc address: {}", d);
        }
    }

    private static String e4mcDomain(net.minecraft.network.chat.Component c) {
        if (c.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc
                && "text.e4mc_minecraft.domainAssigned".equals(tc.getKey())) {
            for (Object a : tc.getArgs()) {
                String s = domainFromArg(a);
                if (s != null) return s;
            }
        }
        for (net.minecraft.network.chat.Component sib : c.getSiblings()) {
            String s = e4mcDomain(sib);
            if (s != null) return s;
        }
        return domainFromText(c.getString());
    }

    /** The domain is either plain text or (when e4mc hides it) a "click to copy" component carrying it. */
    private static String domainFromArg(Object a) {
        if (a instanceof String s) return domainFromText(s);
        if (a instanceof net.minecraft.network.chat.Component cc) {
            if (cc.getStyle().getClickEvent() instanceof net.minecraft.network.chat.ClickEvent.CopyToClipboard cp) {
                String d = domainFromText(cp.value());
                if (d != null) return d;
            }
            return domainFromText(cc.getString());
        }
        return null;
    }

    private static String domainFromText(String text) {
        Matcher m = DOMAIN.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    public void joinByFolder(String folderId) {
        SharedConfig.Entry e = SharedConfig.get().findByFolder(folderId);
        if (e == null) {
            status = "That shared world is no longer in your list.";
            return;
        }
        join(e);
    }

    /** Aborts a join/election that is in progress (not a running host). */
    public void cancel() {
        if (hosting) {
            status = "You are hosting. Leave the world to stop.";
            return;
        }
        active = false;
        connected = false;
        if (lease != null) lease.release();
        status = "Cancelled.";
    }

    /** Puts the connecting screen up (from the render thread); Back/Cancel on it returns to {@code back}. */
    private void openConnectScreen(Screen back, String name) {
        SharedConnectScreen screen = new SharedConnectScreen(back, name);
        connectScreen = screen;
        Minecraft.getInstance().setScreen(screen);
    }

    void connectScreenClosed(SharedConnectScreen screen) {
        if (connectScreen == screen) connectScreen = null;
    }

    public void join(SharedConfig.Entry e) {
        openConnectScreen(Minecraft.getInstance().screen, e.name);
        if (active) {
            status = "Already " + (hosting ? "hosting" : "connecting") + ". Use Cancel first if it looks stuck.";
            return;
        }
        connected = false;
        phaseStartedAt = System.currentTimeMillis();
        if (!FabricLoader.getInstance().isModLoaded("e4mc_minecraft") && !FabricLoader.getInstance().isModLoaded("e4mc")) {
            status = "Install the e4mc mod: it provides the public tunnel the host uses.";
            return;
        }
        active = true;
        entry = e;
        worker.submit(this::run);
    }

    /**
     * The connection to the host ended. If the server closed on us (host left, tunnel or connection died) Minecraft shows a
     * disconnected screen, and we elect a new host. If the player left on purpose they end up at the title or server list
     * instead, and we do nothing. The screen can take a moment to appear, so watch for a few seconds rather than guess.
     */
    private void watchForHostLoss() {
        Thread t = new Thread(() -> {
            Minecraft mc = Minecraft.getInstance();
            for (int i = 0; i < 60 && active && !hosting; i++) { // up to ~6 s
                var screen = mc.screen;
                if (screen instanceof DisconnectedScreen) {
                    tscatemfdh.LOGGER.info("Lost the host's connection; electing a new host");
                    rejoinSoon();
                    return;
                }
                if (screen instanceof TitleScreen || screen instanceof JoinMultiplayerScreen) break; // left on purpose
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (!connected && !hosting) {
                tscatemfdh.LOGGER.info("Left the shared world (screen: {}); not electing a new host", mc.screen);
                active = false;
            }
        }, "tscatemfdh-hostloss");
        t.setDaemon(true);
        t.start();
    }

    private void rejoinSoon() {
        status = "Host left. Electing a new host...";
        Minecraft.getInstance().execute(() -> openConnectScreen(new JoinMultiplayerScreen(new TitleScreen()), entry.name));
        worker.submit(() -> {
            try {
                Thread.sleep(retryBackoffMs);
            } catch (InterruptedException ignored) {
            }
            if (active) run();
        });
    }

    private void run() {
        try {
            InviteCode inv = InviteCode.decode(entry.invite);
            drive = new DriveClient(inv);
            sync = new WorldSync(drive);
            status = "Unlocking...";
            key = resolveKey(entry, sync);
            if (!entry.owner) { // a banned player isn't shown the world at all and gets a plain failure
                boolean banned = amBanned(AccessList.loadAccess(sync, key));
                if (banned != entry.hidden) {
                    entry.hidden = banned;
                    SharedConfig.save();
                    reopenServerListIfOpen();
                }
                if (banned) {
                    fail("Could not join this world right now.");
                    return;
                }
            }
            lease = new Lease(drive, key, Minecraft.getInstance().getUser().getName());
            // Progress from a session of mine that never reached Drive comes first: upload it before anyone loads the world.
            SharedConfig.Pending mine = preparePendingFor(entry);
            if (mine != null) {
                status = "Uploading your unsaved progress...";
                Outcome o = recoverPending(mine);
                if (o == Outcome.FAILED && !askUser("Your last session isn't uploaded yet",
                        "Your progress from the last session is saved on this computer but couldn't be uploaded: " + status
                                + "\n\nLoad the last version from Drive anyway? Your progress stays in the singleplayer world \""
                                + mine.dir + "\" and won't be lost, but it won't be in the shared world.", "Load anyway", "Cancel")) {
                    fail("Your unsaved progress still needs uploading. Try again once Drive is reachable.");
                    return;
                }
            }
            long giveUpAt = System.currentTimeMillis() + 20 * 60_000L; // e.g. the old host is still uploading its save
            while (active && System.currentTimeMillis() < giveUpAt) {
                status = "Looking for a host...";
                if (lease.tryAcquire()) {
                    acquiredAt = System.currentTimeMillis();
                    tscatemfdh.LOGGER.info("Elected host. Leases: {}", lease.describe());
                    try {
                        lease.purgeStale(); // a host that was merely asleep must not come back as the "older" lease
                    } catch (Exception e) {
                        tscatemfdh.LOGGER.warn("Could not clear stale leases", e);
                    }
                    startHeartbeat(); // from now on the lease stays live, even while the world downloads/starts
                    if (!checkLastSession()) {
                        fail("Cancelled. The last session's save didn't finish; ask the player named in the message to upload it.");
                        return;
                    }
                    if (startHosting()) return;
                    Thread.sleep(3000); // someone else won at the same time: go round again and join them
                    continue;
                }
                tscatemfdh.LOGGER.info("Not elected. Leases: {}", lease.describe());
                lease.release(); // lost election: don't keep a competing file around
                var cur = lease.current();
                if (cur.isPresent() && !cur.get().address().isEmpty()) {
                    String addr = cur.get().address();
                    boolean recentlyFailed = addr.equals(lastFailedAddress) && System.currentTimeMillis() - lastFailedAt < 90_000;
                    if (!recentlyFailed) {
                        status = "Joining " + cur.get().holder() + "...";
                        if (connect(addr)) return;
                        lastFailedAddress = addr;
                        lastFailedAt = System.currentTimeMillis();
                        status = "Host unreachable. Retrying...";
                    }
                } else if (cur.isPresent()) {
                    status = cur.get().holder() + " is starting the world...";
                }
                Thread.sleep(3000);
            }
            fail("Timed out waiting for a host.");
        } catch (SecurityException e) {
            fail("Access denied: this world's key no longer matches. Remove it and add it again with the current passcode.");
        } catch (Exception e) {
            tscatemfdh.LOGGER.error("Shared world session failed", e);
            String m = String.valueOf(e.getMessage());
            fail(DriveClient.isAuthExpired(e) ? EXPIRED
                    : m.contains("404") ? "This shared world no longer exists (owner deleted it). Remove it from your list." : "Error: " + m);
        }
    }

    private void cleanupMirror() {
        Path m = mirrorDir;
        mirrorDir = null;
        if (m != null) {
            try {
                WorldSync.deleteTree(m);
            } catch (Exception ignored) {
            }
        }
    }

    static final String EXPIRED = "Google sign-in expired. The world's owner must use \"Refresh Google sign-in\" (in the world's Edit screen), then send everyone the new invite code.";

    private static String friendly(Throwable e) {
        return DriveClient.isAuthExpired(e) ? EXPIRED : String.valueOf(e.getMessage());
    }

    private void fail(String msg) {
        if (hostDirFresh && Files.isDirectory(hostWorldDir)) {
            storeInCache(hostWorldDir, hostLevelId); // the world never started, so this is still exactly what is on Drive
            if (markerWritten) writeSession("clean", "world never started");
            markerWritten = false;
        }
        hostDirFresh = false;
        cleanupMirror();
        status = msg;
        active = false;
        hosting = false;
        stopTicker();
        stopHeartbeat();
        if (lease != null) lease.release();
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatPaused = false;
        hbFailures = 0;
        heartbeatExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tscatemfdh-heartbeat");
            t.setDaemon(true);
            return t;
        });
        Lease l = lease;
        heartbeatExec.scheduleAtFixedRate(() -> {
            if (heartbeatPaused) return;
            try {
                l.heartbeat();
                hbFailures = 0;
            } catch (Exception e) {
                hbFailures++;
                if (DriveClient.isAuthExpired(e)) authExpired = true;
                tscatemfdh.LOGGER.warn("Heartbeat failed ({} in a row)", hbFailures, e);
            }
        }, Lease.HEARTBEAT.toSeconds(), Lease.HEARTBEAT.toSeconds(), TimeUnit.SECONDS);
        heartbeatExec.scheduleWithFixedDelay(this::monitorConnection, 15, 5, TimeUnit.SECONDS);
    }

    private static String e4mcState() {
        try {
            Object session = Class.forName("link.e4mc.E4mcClient").getField("session").get(null);
            if (session == null) return "NONE";
            return String.valueOf(session.getClass().getField("state").get(session));
        } catch (Throwable t) {
            return "UNKNOWN";
        }
    }

    /** Watches the tunnel and the Drive connection; if either is gone, stops sharing but leaves the host in the world. */
    private void monitorConnection() {
        IntegratedServer srv = hostServer;
        if (!hosting || offline || srv == null || domain.get() == null || !srv.isRunning()) return;
        String st = e4mcState();
        boolean tunnelBad = st.equals("UNHEALTHY") || st.equals("STOPPED");
        badTunnelChecks = tunnelBad ? badTunnelChecks + 1 : 0;
        if (badTunnelChecks >= 2) {
            goOffline(srv, "the e4mc tunnel dropped");
            return;
        }
        if (hbFailures >= 3) {
            goOffline(srv, authExpired ? "the Google sign-in expired; the owner must refresh it" : "lost connection to Google Drive");
            return;
        }
        if (++monitorTicks % 3 == 0) { // every ~15 s: is anyone else also hosting this world?
            try {
                splitChecks = lease.isStillWinner() ? 0 : splitChecks + 1;
            } catch (Exception ignored) {
                return; // can't tell right now; hbFailures covers a real outage
            }
            if (splitChecks >= 2) {
                tscatemfdh.LOGGER.warn("Another host is active for this world. Leases: {}", lease.describe());
                goOffline(srv, "another player is also hosting this world");
            }
        }
    }

    /**
     * Stop sharing without kicking the host: other players are removed, the lease is given up so someone else can take
     * over from the last upload, and the host keeps playing. What the host does next is reconciled when they leave.
     */
    private synchronized void goOffline(IntegratedServer srv, String reason) {
        if (offline) return;
        offline = true;
        heartbeatPaused = true;
        stopTicker();
        tscatemfdh.LOGGER.warn("Going offline: {}", reason);
        status = "Connection lost (" + reason + "). Sharing stopped. You can keep playing; your progress is kept safe when you leave.";
        try {
            lease.release(); // frees the election if Drive is still reachable; otherwise it expires on its own
        } catch (Throwable ignored) {
        }
        srv.execute(() -> {
            for (ServerPlayer p : new ArrayList<>(srv.getPlayerList().getPlayers())) {
                if (!p.getUUID().toString().equals(hostUuid)) {
                    p.connection.disconnect(Component.literal("The host lost their connection. Finding a new host..."));
                }
            }
        });
    }

    private void stopHeartbeat() {
        if (heartbeatExec != null) heartbeatExec.shutdownNow();
        heartbeatExec = null;
    }

    private boolean connect(String address) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            // The connecting screen is the parent, so cancelling or failing lands back on our progress screen.
            Screen parent = connectScreen != null ? connectScreen : new JoinMultiplayerScreen(new TitleScreen());
            ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(address),
                    new ServerData(entry.name, address, ServerData.Type.OTHER), false, null);
        });
        startOwnerBackupPoller();
        // If the connection never establishes (host gone, tunnel down), don't stay "active" forever.
        for (int i = 0; i < 60 && active && !connected; i++) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
        }
        return connected;
    }

    /** Returns false if another candidate was elected at the same moment and we stepped aside before opening anything. */
    private boolean startHosting() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        String levelId = "tscatemfdh-" + drive.folderId().substring(0, Math.min(10, drive.folderId().length()));
        Path saves = mc.getLevelSource().getBaseDir();
        hostWorldDir = saves.resolve(levelId);
        hostLevelId = levelId;
        Thread cleanup = backupThread;
        if (cleanup != null && cleanup.isAlive()) {
            status = "Finishing previous save...";
            cleanup.join();
        }
        // Progress left behind by a crash or a failed upload must never be overwritten silently.
        if (Files.exists(hostWorldDir)) preserveAsCopy(hostWorldDir, saves, "recovered");
        // A local copy of each world is kept between sessions; only files that differ from Drive are downloaded.
        Path cacheRoot = cacheRoot();
        Path cache = cacheRoot.resolve(levelId);
        Path dirty = cacheRoot.resolve(levelId + ".dirty");
        Files.createDirectories(cacheRoot);
        if (Files.exists(dirty)) { // a previous sync was interrupted: don't trust that copy
            WorldSync.deleteTree(cache);
            Files.deleteIfExists(dirty);
        }
        status = Files.isDirectory(cache) ? "Checking local copy against Drive..." : "Downloading world...";
        Files.writeString(dirty, "syncing");
        int[] st = sync.downloadInto(cache, key);
        if (st == null) {
            Files.deleteIfExists(dirty);
            fail("No world has been uploaded to this shared world yet.");
            return true;
        }
        Files.deleteIfExists(dirty);
        tscatemfdh.LOGGER.info("World ready: {} files reused from the local copy, {} downloaded", st[0], st[1]);
        moveDir(cache, hostWorldDir);
        hostDirFresh = true;
        baseModified = sync.worldModified();
        WorldIdentity.setSingleplayerUuid(hostWorldDir, mc.getUser().getProfileId(), false);
        int repaired = WorldIdentity.repairPlayerData(hostWorldDir);
        if (repaired > 0) tscatemfdh.LOGGER.info("Repaired {} player data file(s) with a mismatched UUID", repaired);
        // Two players can win the election in the same few seconds (listings lag). Re-check once things have settled, before
        // anything is opened, and let the later one step aside.
        long settle = 6000 - (System.currentTimeMillis() - acquiredAt);
        if (settle > 0) Thread.sleep(settle);
        if (!lease.isStillWinner()) {
            tscatemfdh.LOGGER.warn("Another player was elected at the same time; stepping aside. Leases: {}", lease.describe());
            stopHeartbeat();
            lease.release();
            if (hostDirFresh && Files.isDirectory(hostWorldDir)) storeInCache(hostWorldDir, hostLevelId);
            hostDirFresh = false;
            status = "Another player started the world first. Joining them...";
            return false;
        }
        hostUuid = mc.getUser().getProfileId().toString();
        // Write-ahead note: if this session never finishes saving (crash, lost connection, failed upload), Drive already says so.
        sessionSince = System.currentTimeMillis();
        lastSavedAt = sessionSince;
        writeSession("hosting", "");
        markerWritten = true;
        hosting = true;
        domain.set(null);
        status = "Starting world...";
        mc.execute(() -> mc.createWorldOpenFlows().openWorld(levelId, () -> {
            fail("World loading cancelled.");
        }));
        IntegratedServer server = null;
        for (int i = 0; i < 1800 && active; i++) {
            server = mc.getSingleplayerServer();
            if (server != null && server.isRunning() && mc.player != null) break;
            server = null;
            Thread.sleep(100);
        }
        if (server == null) {
            fail("World failed to start.");
            return true;
        }
        final IntegratedServer srv = server;
        hostServer = srv;
        hostDirFresh = false; // from here on the folder may hold progress, so it is never discarded
        offline = false;
        badTunnelChecks = 0;
        hostUuid = mc.getUser().getProfileId().toString();
        try {
            access = AccessList.loadAccess(sync, key);
            if (access.ownerUuid == null || access.ownerUuid.isEmpty()) access.ownerUuid = entry.owner ? hostUuid : "";
            known = AccessList.loadKnown(sync, key);
            known.online.clear();
            known.touch(hostUuid, mc.getUser().getName());
            known.online.add(hostUuid);
            knownDirty = true;
        } catch (Exception e) {
            tscatemfdh.LOGGER.warn("Could not load player access", e);
        }
        CompletableFuture<Void> opsDone = new CompletableFuture<>();
        srv.execute(() -> {
            try {
                applyOps(srv);
            } catch (Throwable t) {
                tscatemfdh.LOGGER.warn("Could not restore operators", t);
            }
            opsDone.complete(null);
        });
        opsDone.get(30, TimeUnit.SECONDS);
        status = "Opening tunnel...";
        int port = HttpUtil.getAvailablePort();
        srv.execute(() -> srv.publishServer(srv.getDefaultGameType(), false, port));
        for (int i = 0; i < 600 && domain.get() == null && active; i++) Thread.sleep(100);
        if (domain.get() == null) {
            fail("e4mc did not report a public address.");
            mc.execute(() -> srv.halt(false));
            return true;
        }
        lease.publishAddress(domain.get());
        status = "Hosting at " + domain.get();
        startTicker(srv);
            return true;
    }

    private void startTicker(IntegratedServer srv) {
        stopTicker();
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tscatemfdh-ticker");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleAtFixedRate(() -> syncAccess(srv), 5, 15, TimeUnit.SECONDS);
        try {
            mirrorDir = Files.createTempDirectory("tsc-mirror");
        } catch (Exception e) {
            tscatemfdh.LOGGER.warn("Could not create autosave mirror", e);
        }
        // Build the mirror in the background right away, so the first autosave only has to copy what changed.
        ticker.schedule(() -> {
            try {
                long t0 = System.currentTimeMillis();
                int n = WorldSync.syncMirror(srv.getWorldPath(LevelResource.ROOT), mirrorDir);
                tscatemfdh.LOGGER.info("Autosave mirror ready ({} files, {} ms)", n, System.currentTimeMillis() - t0);
            } catch (Exception e) {
                tscatemfdh.LOGGER.warn("Autosave mirror prebuild failed", e);
            }
        }, 3, TimeUnit.SECONDS);
        long every = Math.max(30, SharedConfig.get().autosaveSeconds);
        ticker.scheduleWithFixedDelay(() -> snapshot(srv), every, every, TimeUnit.SECONDS);
    }

    private void stopTicker() {
        if (ticker != null) ticker.shutdownNow();
        ticker = null;
    }

    /**
     * Autosave. The game thread only does Minecraft's own flush-save and then pauses chunk writing (the same thing /save-off
     * does); the world is copied into a mirror folder on this background thread while writing is paused, and then writing
     * resumes. Packing, encrypting and uploading also happen here, from the mirror. This keeps the game thread's share down to
     * the flush itself instead of also walking and copying tens of region files.
     */
    private void snapshot(IntegratedServer srv) {
        Path mirror = mirrorDir;
        if (mirror == null) return;
        autosaving = true;
        long t0 = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicBoolean pausedByUs = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            CompletableFuture<Long> flushed = new CompletableFuture<>();
            srv.execute(() -> {
                long s0 = System.currentTimeMillis();
                try {
                    srv.saveEverything(true, true, true);
                    saveOps(srv);
                    pausedByUs.set(srv.setAutoSave(false)); // true only if saving was on and we turned it off
                    flushed.complete(System.currentTimeMillis() - s0);
                } catch (Throwable e) {
                    flushed.completeExceptionally(e);
                }
            });
            long blockedMs = flushed.get(2, TimeUnit.MINUTES);
            int changed;
            long copyMs;
            try {
                long c0 = System.currentTimeMillis();
                changed = WorldSync.syncMirror(srv.getWorldPath(LevelResource.ROOT), mirror);
                copyMs = System.currentTimeMillis() - c0;
            } finally {
                // Always turn chunk saving back on. Queued behind the pause above, so it is correct even if that was slow.
                srv.execute(() -> {
                    if (pausedByUs.get()) srv.setAutoSave(true);
                });
            }
            long t1 = System.currentTimeMillis();
            var res = sync.uploadTree(mirror, key); // only changed files are sent
            baseModified = res.modified();
            autosaveFailures = 0;
            lastSavedAt = System.currentTimeMillis();
            writeSession("hosting", "");
            sync.uploadIcon(mirror, key);
            long t2 = System.currentTimeMillis();
            mirrorToOwnerSaves(mirror);
            tscatemfdh.LOGGER.info("Autosave done: game thread blocked {} ms (flush); copied {} changed files in {} ms off-thread, saving paused only for that; "
                            + "upload {} ms: {} files in {} pack(s), {} bytes{}",
                    blockedMs, changed, copyMs, t2 - t1, res.filesUploaded(), res.packs(), res.bytesUploaded(), res.full() ? " (full)" : "");
        } catch (Exception e) {
            srv.execute(() -> {
                if (pausedByUs.get()) srv.setAutoSave(true);
            });
            tscatemfdh.LOGGER.warn("Autosave upload failed (will retry)", e);
            if (DriveClient.isAuthExpired(e)) status = EXPIRED;
            if (autosaveFailures++ == 0) { // say so once, not every few minutes
                toast("Autosave to Drive failed", friendly(e) + " Your progress is safe on this computer and uploads when you leave.");
            }
        } finally {
            autosaving = false;
        }
    }

    private void mirrorToOwnerSaves(Path worldDir) {
        if (entry != null && entry.backup && entry.backupName != null) {
            try {
                LocalBackup.write(worldDir, Minecraft.getInstance().getLevelSource().getBaseDir(), entry.backupName);
            } catch (Exception e) {
                tscatemfdh.LOGGER.warn("Local backup failed", e);
            }
        }
    }

    /**
     * Runs when the hosted integrated server has fully stopped: final upload, backup, then release the lease.
     * If the upload fails the lease is KEPT (so nobody else starts from a stale copy) and the upload is retried in
     * the background for a while.
     */
    private void onHostStopped(net.minecraft.server.MinecraftServer server) {
        stopTicker();
        if (offline) {
            Path root = server.getWorldPath(LevelResource.ROOT);
            try {
                saveOps(server);
                restoreHostOps(server);
            } catch (Throwable ignored) {
            }
            hosting = false;
            registerPending();
            if (!reacquireIfUnchanged()) {
                keepOfflineCopy(root);
                endHostSession();
                return;
            }
            offline = false; // nobody else touched the world: upload normally below
        }
        try {
            lease.publishAddress(""); // joiners must wait rather than connect to a closing server
        } catch (Throwable ignored) {
        }
        Path root = server.getWorldPath(LevelResource.ROOT);
        try {
            saveOps(server);
        } catch (Throwable t) {
            tscatemfdh.LOGGER.warn("Could not save operators", t);
        }
        try {
            restoreHostOps(server);
        } catch (Throwable t) {
            tscatemfdh.LOGGER.warn("Could not clean up operators", t);
        }
        hosting = false;
        registerPending(); // local write-ahead: if the game dies during the upload, the next start finds and finishes it
        status = "Saving world to Drive...";
        if (uploadFinal(root, 3)) {
            afterFinalUpload(root);
            return;
        }
        // The upload failed. First make sure the progress is safe on THIS computer as a normal singleplayer world, then say so.
        String saved = preserveUnsaved("unsaved");
        writeSession("failed", lastFailure);
        String where = saved != null ? "saved on this computer as the singleplayer world \"" + saved + "\""
                : "still in your saves folder (" + hostWorldDir.getFileName() + ")";
        status = "Upload to Drive failed. Your progress is " + where + ". It will upload automatically next time.";
        toast("Shared world NOT saved to Drive", "Your progress is " + where + ". Don't delete it. It uploads when Drive works again.");
        SharedConfig.Pending pending = entry == null ? null : findPending(entry.folderId());
        endHostSession(); // let go of the host role so the next player is told about the failed save, not left waiting
        if (pending != null) {
            // Keep trying in the background for a while. This uses the same safe path as the next start: it only uploads if
            // nobody has loaded a different version in the meantime, and it holds the host role while it does.
            worker.submit(() -> {
                for (int i = 0; i < 20; i++) {
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (recoverPending(pending) != Outcome.FAILED) return;
                }
                tscatemfdh.LOGGER.error("Still could not upload the unsaved world; it is kept in {}", pending.dir);
            });
        }
    }

    private boolean uploadFinal(Path root, int attempts) {
        for (int i = 1; i <= attempts; i++) {
            long t0 = System.currentTimeMillis();
            try {
                tscatemfdh.LOGGER.info("Final upload starting (attempt {})", i);
                var res = sync.upload(root, key);
                tscatemfdh.LOGGER.info("Final upload finished in {} ms: {} files in {} pack(s), {} bytes{}",
                        System.currentTimeMillis() - t0, res.filesUploaded(), res.packs(), res.bytesUploaded(), res.full() ? " (full)" : "");
                return true;
            } catch (Throwable t) {
                tscatemfdh.LOGGER.error("FINAL UPLOAD FAILED (attempt " + i + "); world kept locally at " + hostWorldDir, t);
                lastFailure = friendly(t);
                if (DriveClient.isAuthExpired(t)) status = EXPIRED;
                if (i < attempts) {
                    try {
                        Thread.sleep(3000L * i);
                    } catch (InterruptedException e) {
                        return false;
                    }
                }
            }
        }
        return false;
    }

    private volatile Thread backupThread;

    private void afterFinalUpload(Path root) {
        writeSession("clean", "");
        clearPending(entry != null ? entry.folderId() : null);
        try {
            known.online.clear();
            AccessList.saveKnown(sync, key, known);
        } catch (Throwable ignored) {
        }
        status = "World saved and closed.";
        endHostSession(); // release the lease now so the next host can start straight away
        // The owner's local backup copy and the cache cleanup don't need to hold anyone up.
        Thread t = new Thread(() -> {
            try {
                mirrorToOwnerSaves(root);
                storeInCache(hostWorldDir, hostLevelId); // keep it for next time, out of the singleplayer list
            } catch (Throwable ex) {
                tscatemfdh.LOGGER.warn("Cleanup after upload failed", ex);
            }
        }, "tscatemfdh-cleanup");
        t.setDaemon(false);
        backupThread = t;
        t.start();
    }

    /** After being offline: true only if we can prove nobody else changed the shared world meanwhile, and re-take the lease. */
    private boolean reacquireIfUnchanged() {
        try {
            java.time.Instant cur = sync.worldModified();
            if (cur == null || baseModified == null || !cur.equals(baseModified)) return false;
            if (!lease.tryAcquire()) {
                lease.release();
                return false;
            }
            heartbeatPaused = false;
            hbFailures = 0;
            startHeartbeat();
            return true;
        } catch (Throwable t) {
            tscatemfdh.LOGGER.warn("Could not verify the shared world after being offline", t);
            return false;
        }
    }

    /** Not safe to overwrite the shared world: keep the host's version as a separate singleplayer world instead. */
    private void keepOfflineCopy(Path root) {
        String folder = preserveUnsaved("offline copy");
        status = folder != null
                ? "Saved your progress as the singleplayer world \"" + folder + "\". It was not uploaded because the shared world may have changed while you were offline; it uploads automatically if it hasn't."
                : "Could not save an offline copy. Your progress is still in the saves folder.";
        toast("Shared world not uploaded", folder != null ? "Your offline progress is saved as \"" + folder + "\"." : "Your progress is still in your saves folder.");
    }

    private void endHostSession() {
        markerWritten = false;
        hostServer = null;
        offline = false;
        cleanupMirror();
        stopHeartbeat();
        if (lease != null) lease.release(); // only after upload, so a successor never downloads a stale world
        hosting = false;
        active = false;
    }

    /** Owner-only: when someone else hosts, keep pulling the latest world into the owner's saves/ folder. */
    private void startOwnerBackupPoller() {
        if (entry == null || !entry.backup || entry.backupName == null) return;
        stopTicker();
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tscatemfdh-backup");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleWithFixedDelay(() -> {
            try {
                Instant m = sync.worldModified();
                if (m != null && m.isAfter(lastBackedUp)) {
                    Path tmp = Minecraft.getInstance().getLevelSource().getBaseDir().resolve(".tscatemfdh-pull");
                    sync.download(tmp, key);
                    LocalBackup.write(tmp, Minecraft.getInstance().getLevelSource().getBaseDir(), entry.backupName);
                    WorldSync.deleteTree(tmp);
                    lastBackedUp = m;
                }
            } catch (Exception e) {
                tscatemfdh.LOGGER.warn("Owner backup pull failed", e);
            }
        }, 60, 120, TimeUnit.SECONDS);
    }

    /** Owner setup: sign in, create Drive folder, encrypt+upload an existing local world, store the entry. */
    public void shareExisting(String displayName, String savesFolder, String passcode, String clientId, String clientSecret) {
        shareExisting(displayName, savesFolder, passcode, clientId, clientSecret, null);
    }

    public void shareExisting(String displayName, String savesFolder, String passcode, String clientId, String clientSecret, Runnable onDone) {
        for (SharedConfig.Entry o : SharedConfig.get().worlds) {
            if (o.owner && savesFolder.equals(o.localBackupName)) {
                status = "That world is already shared (" + o.name + ").";
                if (onDone != null) onDone.run();
                return;
            }
        }
        if (displayName.isBlank() || savesFolder.isBlank() || clientId.isBlank() || clientSecret.isBlank()) {
            status = "Fill in name, world and Google client id/secret.";
            if (onDone != null) onDone.run();
            return;
        }
        worker.submit(() -> {
            try {
                status = "Opening Google sign-in in your browser...";
                String refresh = OwnerSignIn.signIn(clientId, clientSecret,
                        url -> net.minecraft.util.Util.getPlatform().openUri(url));
                InviteCode partial = new InviteCode(clientId, clientSecret, refresh, "", displayName);
                status = "Creating Drive folder...";
                String folder = OwnerSignIn.createFolder(partial, displayName);
                InviteCode inv = new InviteCode(clientId, clientSecret, refresh, folder, displayName);
                DriveClient d = new DriveClient(inv);
                WorldSync s = new WorldSync(d);
                SecretKey k = s.initialise(passcode);
                status = "Uploading world (encrypted)...";
                Path src = Minecraft.getInstance().getLevelSource().getBaseDir().resolve(savesFolder);
                Path staging = Files.createTempDirectory("tsc-share");
                try {
                    Path copy = staging.resolve("world");
                    copyTree(src, copy);
                    WorldIdentity.setSingleplayerUuid(copy, Minecraft.getInstance().getUser().getProfileId(), true);
                    s.upload(copy, k);
                    AccessList.Access acc = new AccessList.Access();
                    acc.ownerUuid = Minecraft.getInstance().getUser().getProfileId().toString();
                    AccessList.saveAccess(s, k, acc);
                } finally {
                    WorldSync.deleteTree(staging);
                }
                SharedConfig.Entry e = new SharedConfig.Entry();
                e.name = displayName;
                e.invite = inv.encode();
                e.passcode = passcode;
                e.key = java.util.Base64.getEncoder().encodeToString(k.getEncoded());
                e.owner = true;
                e.filed = true; // created inside the shared worlds folder already
                e.localBackupName = savesFolder;
                e.backup = true;
                e.backupName = savesFolder;
                e.backupMigrated = true;
                SharedConfig cfg = SharedConfig.get();
                cfg.oauthClientId = clientId;
                cfg.oauthClientSecret = clientSecret;
                cfg.worlds.add(e);
                SharedConfig.save();
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().keyboardHandler.setClipboard(e.invite));
                status = "Shared! Invite code copied to clipboard. Send it + the passcode (separately) to friends.";
            } catch (Exception ex) {
                tscatemfdh.LOGGER.error("Share failed", ex);
                status = "Share failed: " + ex.getMessage();
            } finally {
                if (onDone != null) onDone.run();
            }
        });
    }

    /** Friend setup: validate an invite + passcode and remember it. */
    public void addInvite(String invite, String passcode) {
        worker.submit(() -> {
            try {
                InviteCode inv = InviteCode.decode(invite);
                SharedConfig.Entry existing = SharedConfig.get().findByFolder(inv.folderId());
                if (existing != null) {
                    if (existing.invite.equals(invite.trim())) {
                        status = "That shared world is already in your list.";
                        return;
                    }
                    // same world, new invite: the owner refreshed the Google sign-in. Check it, then swap it in.
                    WorldSync ws = new WorldSync(new DriveClient(inv));
                    if (existing.key != null) ws.verifyKey(new javax.crypto.spec.SecretKeySpec(java.util.Base64.getDecoder().decode(existing.key), "AES"));
                    else existing.key = java.util.Base64.getEncoder().encodeToString(ws.unlock(passcode).getEncoded());
                    existing.invite = invite.trim();
                    SharedConfig.save();
                    status = "Updated Google access for " + existing.name + ".";
                    return;
                }
                SecretKey k = new WorldSync(new DriveClient(inv)).unlock(passcode);
                SharedConfig.Entry e = new SharedConfig.Entry();
                e.key = java.util.Base64.getEncoder().encodeToString(k.getEncoded());
                e.name = inv.name();
                e.invite = invite.trim();
                SharedConfig.get().worlds.add(e);
                SharedConfig.save();
                status = "";
            } catch (SecurityException ex) {
                status = "Wrong passcode.";
            } catch (Exception ex) {
                if (DriveClient.isAuthExpired(ex)) {
                    // Can't check the passcode with a dead sign-in, but keep the world so it can be renewed from this list.
                    try {
                        InviteCode inv = InviteCode.decode(invite);
                        if (SharedConfig.get().findByFolder(inv.folderId()) == null) {
                            SharedConfig.Entry e = new SharedConfig.Entry();
                            e.name = inv.name();
                            e.invite = invite.trim();
                            e.passcode = passcode; // used to unlock it once access works again
                            SharedConfig.get().worlds.add(e);
                            SharedConfig.save();
                        }
                    } catch (Exception ignored) {
                    }
                    status = "Added, but its Google sign-in has expired. If the world is in your Drive, use Refresh Google sign-in in its Edit screen; otherwise its owner must.";
                    return;
                }
                status = "Could not add: " + friendly(ex);
            }
        });
    }

    private static void copyTree(Path src, Path dst) throws java.io.IOException {
        try (var w = Files.walk(src)) {
            for (Path p : (Iterable<Path>) w::iterator) {
                if (p.getFileName() != null && p.getFileName().toString().equals("session.lock")) continue;
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else {
                    Files.createDirectories(t.getParent());
                    Files.copy(p, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Owner: deletes the Drive folder (world gone for everyone). Others: just forget it locally.
     * The local entry is removed immediately (before the multiplayer screen reopens and re-syncs its list);
     * if the Drive delete fails for the owner, the entry is put back.
     */
    public void delete(SharedConfig.Entry e) {
        if (entry != null && entry.folderId().equals(e.folderId())) {
            if (hosting || connected) {
                status = "Leave the shared world before deleting it.";
                return;
            }
            active = false; // abandon a stuck/in-progress join of this world
            if (lease != null) lease.release();
        }
        SharedConfig.get().worlds.remove(e);
        SharedConfig.save();
        status = "";
        try {
            String id = e.folderId();
            WorldSync.deleteTree(cacheRoot().resolve("tscatemfdh-" + id.substring(0, Math.min(10, id.length()))));
        } catch (Exception ignored) {
        }
        if (!e.owner) return;
        worker.submit(() -> {
            try {
                new DriveClient(InviteCode.decode(e.invite)).delete(e.folderId());
            } catch (Exception ex) {
                tscatemfdh.LOGGER.error("Delete failed", ex);
                if (DriveClient.isAuthExpired(ex)) {
                    // Can't reach Drive with the dead sign-in, so there's nothing to keep: it is gone from the list, and the
                    // folder has to be removed in Drive by hand (or after refreshing the sign-in).
                    status = "Removed from your list. Google sign-in expired, so delete the Drive folder \"" + e.name
                            + "\" yourself (in Minecraft Shared Worlds), or refresh the sign-in and add the world back to delete it here.";
                    return;
                }
                SharedConfig.get().worlds.add(e);
                SharedConfig.save();
                status = "Delete failed, world kept: " + friendly(ex);
            }
        });
    }

    /** Manual, on-demand copy of the latest Drive version into a NEW singleplayer world (never overwrites). */
    public void copyToSingleplayer(SharedConfig.Entry e) {
        if (active && entry != null && entry.folderId().equals(e.folderId()) && hosting) {
            status = "Leave the world first so the latest version is uploaded, then copy.";
            return;
        }
        worker.submit(() -> {
            try {
                status = "Copying " + e.name + " to singleplayer...";
                InviteCode inv = InviteCode.decode(e.invite);
                DriveClient d = new DriveClient(inv);
                WorldSync s = new WorldSync(d);
                SecretKey k = resolveKey(e, s);
                Path saves = Minecraft.getInstance().getLevelSource().getBaseDir();
                String base = e.name.replaceAll("[^A-Za-z0-9 _.-]", "_").trim() + " (copy)";
                String folder = base;
                for (int i = 2; Files.exists(saves.resolve(folder)); i++) folder = base + " " + i;
                Path dest = saves.resolve(folder);
                if (!s.download(dest, k)) {
                    status = "Nothing to copy yet.";
                    return;
                }
                WorldIdentity.setSingleplayerUuid(dest, Minecraft.getInstance().getUser().getProfileId(), false);
                WorldIdentity.setLevelName(dest, folder);
                status = "Copied to singleplayer world \"" + folder + "\".";
            } catch (Exception ex) {
                tscatemfdh.LOGGER.error("Copy failed", ex);
                status = "Copy failed: " + friendly(ex);
            }
        });
    }

    /**
     * Creates the paused new world (vanilla finishes it as if "Create New World" were just pressed), then as soon as
     * it has loaded we save and quit it and upload the result through the normal share path.
     */
    public void createAndShareNew(String name, String passcode, String clientId, String clientSecret, Runnable onDone) {
        var screen = ShareDraft.pendingScreen;
        if (screen == null) {
            status = "Nothing to create.";
            onDone.run();
            return;
        }
        try {
            if (ShareDraft.pendingPackDir != null && ShareDraft.pendingPackBackup != null
                    && !Files.exists(ShareDraft.pendingPackDir)) { // vanilla removed it when the callback returned
                copyTree(ShareDraft.pendingPackBackup, ShareDraft.pendingPackDir);
            }
            newWorldShare = new NewWorldShare(name, passcode, clientId, clientSecret, onDone);
            newWorldFolder = null;
            status = "Creating world...";
            ((net.ethug.tscatemfdh.mixin.CreateWorldScreenInvoker) screen).tscatemfdh$createNewWorld(
                    ShareDraft.pendingRegistries, ShareDraft.pendingData, ShareDraft.pendingRules);
            ShareDraft.clearPending();
        } catch (Throwable t) {
            tscatemfdh.LOGGER.error("Creating world for sharing failed", t);
            newWorldShare = null;
            status = "Could not create world: " + t.getMessage();
            onDone.run();
        }
    }

    private void onNewWorldLoaded(Minecraft mc) {
        IntegratedServer srv = mc.getSingleplayerServer();
        newWorldFolder = srv.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
        status = "World created. Saving and uploading...";
        mc.execute(() -> mc.execute(mc::disconnectWithSavingScreen));
    }

    private void finishNewWorldShare() {
        NewWorldShare n = newWorldShare;
        String folder = newWorldFolder;
        newWorldShare = null;
        newWorldFolder = null;
        shareExisting(n.name(), folder, n.passcode(), n.clientId(), n.clientSecret(), n.onDone());
    }

    /** Owner: set a new passcode for new connections (any length, may be empty). Existing members keep playing. */
    public void setPasscode(SharedConfig.Entry e, String newPasscode) {
        if (!e.owner || newPasscode == null || newPasscode.equals(e.passcode == null ? "" : e.passcode)) return;
        worker.submit(() -> {
            try {
                status = "Changing passcode...";
                DriveClient d = new DriveClient(InviteCode.decode(e.invite));
                WorldSync s = new WorldSync(d);
                SecretKey k = resolveKey(e, s);
                s.rewrap(k, newPasscode);
                e.passcode = newPasscode;
                SharedConfig.save();
                status = "Passcode changed. It only applies to new connections.";
            } catch (Exception ex) {
                tscatemfdh.LOGGER.error("Passcode change failed", ex);
                status = "Passcode change failed: " + friendly(ex);
            }
        });
    }

    /** Master key for an entry: the stored key (verified), or derived once from the passcode and then stored. */
    private SecretKey resolveKey(SharedConfig.Entry e, WorldSync s) throws Exception {
        if (e.key != null) {
            SecretKey k = new javax.crypto.spec.SecretKeySpec(java.util.Base64.getDecoder().decode(e.key), "AES");
            try {
                s.verifyKey(k);
                return k;
            } catch (SecurityException ex) {
                if (e.passcode == null) throw ex;
            }
        }
        SecretKey k = s.unlock(e.passcode == null ? "" : e.passcode);
        e.key = java.util.Base64.getEncoder().encodeToString(k.getEncoded());
        SharedConfig.save();
        return k;
    }

    // ---- player access (whitelist / bans / player list) ----

    private volatile AccessList.Access access = new AccessList.Access();
    private volatile AccessList.Known known = new AccessList.Known();
    private volatile String hostUuid = "";
    private volatile boolean knownDirty;

    public static void registerAccess() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (INSTANCE.hosting && server instanceof IntegratedServer) INSTANCE.admit(handler.getPlayer());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (INSTANCE.hosting && server instanceof IntegratedServer) {
                INSTANCE.known.online.remove(handler.getPlayer().getUUID().toString());
                INSTANCE.knownDirty = true;
                INSTANCE.flushKnownAsync();
            }
        });
    }

    /** Pushes the player list to Drive right away, off the game thread so the server never waits on the network. */
    private void flushKnownAsync() {
        worker.submit(() -> {
            try {
                if (knownDirty) {
                    knownDirty = false;
                    AccessList.saveKnown(sync, key, known);
                }
            } catch (Exception e) {
                knownDirty = true;
                tscatemfdh.LOGGER.warn("Player list push failed (will retry)", e);
            }
        });
    }

    private void admit(ServerPlayer p) {
        String uuid = p.getUUID().toString();
        tscatemfdh.LOGGER.info("Player joined shared world: {} ({}), host is {}", p.getName().getString(), uuid, hostUuid);
        known.touch(uuid, p.getName().getString());
        if (!known.online.contains(uuid)) known.online.add(uuid);
        knownDirty = true;
        flushKnownAsync();
        enforce(p);
    }

    private void enforce(ServerPlayer p) {
        String uuid = p.getUUID().toString();
        if (offline && !uuid.equals(hostUuid)) {
            p.connection.disconnect(Component.literal("The host lost their connection. Finding a new host..."));
            return;
        }
        String name = p.getName().getString();
        if (access.allows(uuid, name, hostUuid)) return;
        known.online.remove(uuid);
        Component msg = access.isBanned(uuid, name) ? GENERIC_DENIAL
                : Component.literal("You are not on this shared world's whitelist. Ask the owner to add you.");
        p.connection.disconnect(msg);
    }

    /** Called from the host ticker: pull owner edits, re-check online players, push the player list. */
    private void syncAccess(IntegratedServer srv) {
        try {
            access = AccessList.loadAccess(sync, key);
            if (knownDirty) {
                knownDirty = false;
                AccessList.saveKnown(sync, key, known);
            }
            srv.execute(() -> {
                try {
                    applyPermissionEdits(srv);
                    reportPermissions(srv);
                } catch (Throwable t) {
                    tscatemfdh.LOGGER.warn("Permission sync failed", t);
                }
                for (ServerPlayer p : new ArrayList<>(srv.getPlayerList().getPlayers())) enforce(p);
            });
        } catch (Exception e) {
            tscatemfdh.LOGGER.warn("Access sync failed (will retry)", e);
        }
    }

    private final java.util.Map<String, String> appliedPerms = new ConcurrentHashMap<>();

    /** Applies permission levels the owner changed in the player list (only when they change, so in-game /op still works). */
    private void applyPermissionEdits(IntegratedServer srv) {
        net.minecraft.server.players.PlayerList pl = srv.getPlayerList();
        for (var en : access.perms.entrySet()) {
            String uuid = en.getKey(), level = en.getValue();
            if (level.equals(appliedPerms.get(uuid)) || uuid.equals(access.ownerUuid)) continue;
            appliedPerms.put(uuid, level);
            String name = known.players.stream().filter(p -> uuid.equals(p.uuid)).map(p -> p.name).findFirst().orElse("player");
            var id = new net.minecraft.server.players.NameAndId(java.util.UUID.fromString(uuid), name);
            if (level.equals("ALL")) {
                if (pl.getOps().get(id) != null) pl.deop(id);
            } else {
                pl.op(id, Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.forLevel(
                        net.minecraft.server.permissions.PermissionLevel.valueOf(level))), Optional.of(false));
            }
            addedOps.remove(uuid); // now a deliberate grant, saved with the world
        }
    }

    /** Publishes who is an operator, and at what level, so everyone can see it in the player list. */
    private void reportPermissions(IntegratedServer srv) {
        java.util.Map<String, String> now = new java.util.HashMap<>();
        for (var e : srv.getPlayerList().getOps().getEntries()) {
            now.put(e.getUser().id().toString(), e.permissions().level().name());
        }
        if (access.ownerUuid != null && !access.ownerUuid.isEmpty()) now.put(access.ownerUuid, "OWNERS");
        if (!now.equals(known.perms)) {
            known.perms = now;
            knownDirty = true;
        }
    }

    // ---- owner: manage players from the edit screen ----

    /** Folder id of the shared world this client is currently playing in (hosting or joined), else null. */
    public String activeFolderId() {
        SharedConfig.Entry e = entry;
        return (active && e != null && (hosting || connected)) ? e.folderId() : null;
    }

    public void loadPlayers(SharedConfig.Entry e, java.util.function.BiConsumer<AccessList.Access, AccessList.Known> done) {
        worker.submit(() -> {
            try {
                WorldSync s = new WorldSync(new DriveClient(InviteCode.decode(e.invite)));
                SecretKey k = resolveKey(e, s);
                AccessList.Access a = AccessList.loadAccess(s, k);
                if (e.owner && (a.ownerUuid == null || a.ownerUuid.isEmpty())) a.ownerUuid = Minecraft.getInstance().getUser().getProfileId().toString();
                done.accept(a, AccessList.loadKnown(s, k));
            } catch (Exception ex) {
                tscatemfdh.LOGGER.error("Loading players failed", ex);
                status = "Could not load players: " + friendly(ex);
            }
        });
    }

    public void savePlayers(SharedConfig.Entry e, AccessList.Access a) {
        worker.submit(() -> {
            try {
                WorldSync s = new WorldSync(new DriveClient(InviteCode.decode(e.invite)));
                AccessList.saveAccess(s, resolveKey(e, s), a);
                status = "Player access saved. Takes effect within ~15 seconds.";
            } catch (Exception ex) {
                tscatemfdh.LOGGER.error("Saving players failed", ex);
                status = "Could not save: " + friendly(ex);
            }
        });
    }

    /** Pulls each world's icon.png from Drive (small, cached) and updates the open server list as they arrive. */
    public void refreshIcons() {
        for (SharedConfig.Entry e : new ArrayList<>(SharedConfig.get().worlds)) {
            if (e.key == null) continue;
            worker.submit(() -> {
                try {
                    WorldSync s = new WorldSync(new DriveClient(InviteCode.decode(e.invite)));
                    Instant m = s.iconModified();
                    if (m == null || m.toString().equals(IconStore.stamp(e.folderId()))) return;
                    SecretKey k = new javax.crypto.spec.SecretKeySpec(java.util.Base64.getDecoder().decode(e.key), "AES");
                    byte[] png = s.downloadIcon(k);
                    if (png == null) return;
                    IconStore.write(e.folderId(), png, m.toString());
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> {
                        if (mc.screen instanceof JoinMultiplayerScreen jm) {
                            ServerData d = jm.getServers().get(SharedServerList.PREFIX + e.folderId());
                            if (d != null) d.setIconBytes(png);
                        }
                    });
                } catch (Exception ex) {
                    tscatemfdh.LOGGER.debug("Icon refresh failed", ex);
                }
            });
        }
    }

    // ---- operators persist with the world ----

    private final java.util.Set<String> addedOps = ConcurrentHashMap.newKeySet();

    /** Re-applies the world's saved operators on whoever hosts (and always ops the world's owner). Server thread. */
    private void applyOps(IntegratedServer srv) throws Exception {
        net.minecraft.server.players.PlayerList pl = srv.getPlayerList();
        Path f = hostWorldDir.resolve(STATE_FILE);
        addedOps.clear();
        if (Files.exists(f)) {
            var arr = com.google.gson.JsonParser.parseString(Files.readString(f)).getAsJsonObject().getAsJsonArray("ops");
            for (var el : arr) {
                var o = el.getAsJsonObject();
                grantOp(pl, o.get("uuid").getAsString(), o.get("name").getAsString(),
                        net.minecraft.server.permissions.PermissionLevel.valueOf(o.get("level").getAsString()),
                        o.has("bypass") && o.get("bypass").getAsBoolean());
            }
        }
        String owner = access.ownerUuid;
        if (owner != null && !owner.isEmpty()) {
            String name = known.players.stream().filter(p -> owner.equals(p.uuid)).map(p -> p.name).findFirst().orElse("owner");
            grantOp(pl, owner, name, net.minecraft.server.permissions.PermissionLevel.OWNERS, false);
        }
    }

    private void grantOp(net.minecraft.server.players.PlayerList pl, String uuid, String name,
                         net.minecraft.server.permissions.PermissionLevel level, boolean bypass) {
        var id = new net.minecraft.server.players.NameAndId(java.util.UUID.fromString(uuid), name);
        if (pl.getOps().get(id) != null) return; // host already ops this player themselves
        addedOps.add(uuid);
        pl.op(id, Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.forLevel(level)), Optional.of(bypass));
    }

    /** Writes the operators of known members into the world folder so they travel with the world. */
    private void saveOps(net.minecraft.server.MinecraftServer srv) {
        try {
            var arr = new com.google.gson.JsonArray();
            java.util.Set<String> members = new java.util.HashSet<>();
            for (AccessList.Person p : known.players) members.add(p.uuid);
            for (var e : srv.getPlayerList().getOps().getEntries()) {
                var u = e.getUser();
                if (!members.contains(u.id().toString())) continue; // the host's personal ops stay personal
                var o = new com.google.gson.JsonObject();
                o.addProperty("uuid", u.id().toString());
                o.addProperty("name", u.name());
                o.addProperty("level", e.permissions().level().name());
                o.addProperty("bypass", e.getBypassesPlayerLimit());
                arr.add(o);
            }
            var root = new com.google.gson.JsonObject();
            root.add("ops", arr);
            Files.writeString(srv.getWorldPath(LevelResource.ROOT).resolve(STATE_FILE), root.toString());
        } catch (Exception e) {
            tscatemfdh.LOGGER.warn("Could not save operators", e);
        }
    }

    /** Removes the operators we added from this machine's own ops.json so shared-world ops don't leak into it. */
    private void restoreHostOps(net.minecraft.server.MinecraftServer srv) {
        try {
            for (String uuid : addedOps) {
                srv.getPlayerList().getOps().remove(new net.minecraft.server.players.NameAndId(java.util.UUID.fromString(uuid), ""));
            }
        } catch (Exception e) {
            tscatemfdh.LOGGER.warn("Could not clean up operators", e);
        }
        addedOps.clear();
    }

    /**
     * Owner, once per world: moves its Drive folder into the single "Minecraft Shared Worlds" folder. The folder id (and so
     * every invite code) stays the same, so nobody has to re-add anything.
     */
    public void organizeDrive() {
        for (SharedConfig.Entry e : new ArrayList<>(SharedConfig.get().worlds)) {
            if (!e.owner || e.filed) continue;
            worker.submit(() -> {
                try {
                    DriveClient d = new DriveClient(InviteCode.decode(e.invite));
                    d.moveToFolder(e.folderId(), d.findOrCreateWorldsFolder(OwnerSignIn.WORLDS_FOLDER));
                    e.filed = true;
                    SharedConfig.save();
                    tscatemfdh.LOGGER.info("Moved '{}' into the shared worlds folder", e.name);
                } catch (Exception ex) {
                    tscatemfdh.LOGGER.warn("Could not organize Drive folder for {} (will retry)", e.name, ex);
                }
            });
        }
    }

    // ---- local copy of each hosted world ----

    private static Path cacheRoot() {
        return FabricLoader.getInstance().getGameDir().resolve("tscatemfdh-cache");
    }

    private static void moveDir(Path src, Path dst) throws java.io.IOException {
        try {
            Files.move(src, dst);
        } catch (java.io.IOException e) { // e.g. different drives: copy instead
            WorldSync.deleteTree(dst);
            WorldSync.syncMirror(src, dst);
            WorldSync.deleteTree(src);
        }
    }

    /** After a successful upload the hosted folder is exactly what is on Drive: move it out of saves/ as the local copy. */
    private void storeInCache(Path worldDir, String levelId) {
        try {
            Path cache = cacheRoot().resolve(levelId);
            Files.createDirectories(cache.getParent());
            WorldSync.deleteTree(cache);
            moveDir(worldDir, cache);
        } catch (Throwable t) {
            tscatemfdh.LOGGER.warn("Could not keep a local copy; removing it", t);
            try {
                WorldSync.deleteTree(worldDir);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Keeps a folder as a normal singleplayer world named "<world> (<suffix>)"; returns the folder name. */
    private String preserveAsCopy(Path dir, Path saves, String suffix) {
        try {
            String base = (entry != null ? entry.name : "Shared world").replaceAll("[^A-Za-z0-9 _.-]", "_").trim() + " (" + suffix + ")";
            String folder = base;
            for (int i = 2; Files.exists(saves.resolve(folder)); i++) folder = base + " " + i;
            moveDir(dir, saves.resolve(folder));
            WorldIdentity.setLevelName(saves.resolve(folder), folder);
            tscatemfdh.LOGGER.warn("Kept unsynced progress as '{}'", folder);
            return folder;
        } catch (Throwable t) {
            tscatemfdh.LOGGER.error("Could not preserve " + dir, t);
            return null;
        }
    }

    // ---- description & local backup toggle ----

    private static final String INFO_FILE = "info.json";

    public static String descriptionOf(String folderId) {
        SharedConfig.Entry e = SharedConfig.get().findByFolder(folderId);
        return e == null || e.description == null ? "" : e.description;
    }

    /** Owner: set the description everyone sees under the world's name (empty = none). Stored encrypted on Drive. */
    public void setDescription(SharedConfig.Entry e, String text) {
        String t = text == null ? "" : text.strip();
        e.description = t;
        SharedConfig.save();
        applyDescriptionToList(e);
        worker.submit(() -> {
            try {
                WorldSync s = new WorldSync(new DriveClient(InviteCode.decode(e.invite)));
                var o = new com.google.gson.JsonObject();
                o.addProperty("description", t);
                s.writeJson(INFO_FILE, resolveKey(e, s), o.toString());
            } catch (Exception ex) {
                tscatemfdh.LOGGER.error("Saving description failed", ex);
                status = "Could not save the description: " + friendly(ex);
            }
        });
    }

    /** Pulls each world's description from Drive and updates the server list. */
    public void refreshDescriptions() {
        for (SharedConfig.Entry e : new ArrayList<>(SharedConfig.get().worlds)) {
            if (e.key == null || e.owner) continue; // owners already have the latest locally
            worker.submit(() -> {
                try {
                    WorldSync s = new WorldSync(new DriveClient(InviteCode.decode(e.invite)));
                    SecretKey k = new javax.crypto.spec.SecretKeySpec(java.util.Base64.getDecoder().decode(e.key), "AES");
                    String json = s.readJson(INFO_FILE, k);
                    String d = json == null ? "" : com.google.gson.JsonParser.parseString(json).getAsJsonObject().get("description").getAsString();
                    if (!d.equals(e.description == null ? "" : e.description)) {
                        e.description = d;
                        SharedConfig.save();
                        applyDescriptionToList(e);
                    }
                } catch (Exception ex) {
                    tscatemfdh.LOGGER.debug("Description refresh failed", ex);
                }
            });
        }
    }

    private static void applyDescriptionToList(SharedConfig.Entry e) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.screen instanceof JoinMultiplayerScreen jm) {
                ServerData d = jm.getServers().get(SharedServerList.PREFIX + e.folderId());
                if (d != null) SharedServerList.applyLive(d);
            }
        });
    }

    // ---- who is in each world (shown in the Worlds tab) ----

    private final java.util.Map<String, java.util.List<String>> onlineNames = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String lastOnlineSnapshot = "";
    private final java.util.concurrent.atomic.AtomicBoolean onlineBusy = new java.util.concurrent.atomic.AtomicBoolean();

    public static java.util.List<String> onlineOf(String folderId) {
        return INSTANCE.onlineNames.getOrDefault(folderId, java.util.List.of());
    }

    /** Reads each world's player list from Drive. Names only count while someone is actually hosting (a live lease). */
    public void refreshOnline() {
        if (!onlineBusy.compareAndSet(false, true)) return;
        worker.submit(() -> {
            try {
                for (SharedConfig.Entry e : new ArrayList<>(SharedConfig.get().worlds)) {
                    if (e.hidden || e.key == null) continue;
                    try {
                        DriveClient dc = new DriveClient(InviteCode.decode(e.invite));
                        WorldSync s = new WorldSync(dc);
                        SecretKey k = resolveKey(e, s);
                        java.util.List<String> names = new ArrayList<>();
                        if (new Lease(dc, k, Minecraft.getInstance().getUser().getName()).current().isPresent()) {
                            AccessList.Known kn = AccessList.loadKnown(s, k);
                            for (String uuid : kn.online) {
                                kn.players.stream().filter(p -> uuid.equals(p.uuid)).map(p -> p.name).findFirst().ifPresent(names::add);
                            }
                            names.sort(String.CASE_INSENSITIVE_ORDER);
                        }
                        onlineNames.put(e.folderId(), names);
                    } catch (Exception ex) {
                        tscatemfdh.LOGGER.debug("Online refresh failed for {}", e.name, ex);
                    }
                }
            } finally {
                onlineBusy.set(false);
            }
            Minecraft mc = Minecraft.getInstance();
            String snapshot = onlineNames.toString();
            mc.execute(() -> {
                if (mc.screen instanceof JoinMultiplayerScreen jm) {
                    for (int i = 0; i < jm.getServers().size(); i++) {
                        ServerData d = jm.getServers().get(i);
                        if (SharedServerList.isShared(d)) SharedServerList.applyLive(d);
                    }
                    if (!snapshot.equals(lastOnlineSnapshot)) { // someone joined or left a world: re-sort the list
                        lastOnlineSnapshot = snapshot;
                        ((net.ethug.tscatemfdh.mixin.JoinMultiplayerScreenAccessor) jm).tscatemfdh$getList().updateOnlineServers(jm.getServers());
                    }
                }
            });
        });
    }

    /** Per player: also keep a local singleplayer copy of this world up to date on every autosave. */
    public void toggleBackup(SharedConfig.Entry e) {
        e.backup = !e.backup;
        if (e.backup && e.backupName == null) {
            Path saves = Minecraft.getInstance().getLevelSource().getBaseDir();
            String base = e.name.replaceAll("[^A-Za-z0-9 _.-]", "_").trim() + " (shared backup)";
            String folder = base;
            for (int i = 2; Files.exists(saves.resolve(folder)); i++) folder = base + " " + i;
            e.backupName = folder;
        }
        SharedConfig.save();
    }

    // ---- banned players: never shown the world, refused at login without saying why ----

    private static final Component GENERIC_DENIAL = Component.translatable("disconnect.lost");

    private boolean amBanned(AccessList.Access a) {
        var u = Minecraft.getInstance().getUser();
        String uuid = u.getProfileId().toString();
        return !uuid.equals(a.ownerUuid) && a.isBanned(uuid, u.getName());
    }

    private static void reopenServerListIfOpen() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.screen instanceof JoinMultiplayerScreen) mc.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
        });
    }

    /** Hides (or un-hides) worlds from this player according to the owner's ban list. */
    public void refreshAccessState() {
        for (SharedConfig.Entry e : new ArrayList<>(SharedConfig.get().worlds)) {
            if (e.owner || e.key == null) continue;
            worker.submit(() -> {
                try {
                    WorldSync s = new WorldSync(new DriveClient(InviteCode.decode(e.invite)));
                    SecretKey k = new javax.crypto.spec.SecretKeySpec(java.util.Base64.getDecoder().decode(e.key), "AES");
                    boolean banned = amBanned(AccessList.loadAccess(s, k));
                    if (banned != e.hidden) {
                        e.hidden = banned;
                        SharedConfig.save();
                        reopenServerListIfOpen();
                    }
                } catch (Exception ex) {
                    tscatemfdh.LOGGER.debug("Access refresh failed", ex);
                }
            });
        }
    }

    /**
     * Called by the server while a player is logging in. Returns a disconnect reason, or null to let them in. Banned players get
     * a plain "Connection Lost" and are not told why.
     */
    public Component loginDenial(net.minecraft.server.players.NameAndId id) {
        String uuid = id.id().toString(), name = id.name();
        if (uuid.equals(hostUuid)) return null;
        if (offline) return GENERIC_DENIAL;
        if (uuid.equals(access.ownerUuid)) return null;
        boolean banned = access.isBanned(uuid, name);
        boolean unlisted = access.whitelistEnabled && !access.isWhitelisted(uuid);
        if (banned || unlisted) { // still recorded, so the owner can see who tried and decide
            known.touch(uuid, name);
            knownDirty = true;
        }
        if (banned) return GENERIC_DENIAL;
        if (unlisted) return Component.literal("You are not on this shared world's whitelist. Ask the owner to add you.");
        return null;
    }

    /**
     * Sign in to Google again and put the fresh access into every world whose saved sign-in has expired AND whose Drive
     * folder lives in the account you sign in with. It does not matter who made the Minecraft world or whether the mod
     * currently lists it as yours: if your Google account can reach the folder, you are the one who can renew it, and the
     * world is then listed as yours. Invite codes change, so friends paste the new code into "Add world" once.
     */
    public void refreshGoogleSignIn() {
        java.util.List<SharedConfig.Entry> all = new ArrayList<>(SharedConfig.get().worlds);
        if (all.isEmpty()) {
            status = "There are no shared worlds in your list.";
            return;
        }
        worker.submit(() -> {
            try {
                // Which worlds actually have a dead sign-in? Group them by the Google app (client id) they were made with.
                java.util.Map<String, java.util.List<SharedConfig.Entry>> byClient = new java.util.LinkedHashMap<>();
                for (SharedConfig.Entry e : all) {
                    InviteCode inv = InviteCode.decode(e.invite);
                    try {
                        new DriveClient(inv).getModified(inv.folderId());
                    } catch (Exception probe) {
                        if (DriveClient.isAuthExpired(probe)) byClient.computeIfAbsent(inv.clientId(), k -> new ArrayList<>()).add(e);
                    }
                }
                if (byClient.isEmpty()) {
                    status = "All your shared worlds' Google sign-ins are still valid.";
                    return;
                }
                int updated = 0, skipped = 0;
                for (var group : byClient.entrySet()) {
                    InviteCode first = InviteCode.decode(group.getValue().get(0).invite);
                    status = "Opening Google sign-in in your browser...";
                    String refresh = OwnerSignIn.signIn(first.clientId(), first.clientSecret(),
                            url -> net.minecraft.util.Util.getPlatform().openUri(url));
                    for (SharedConfig.Entry e : group.getValue()) {
                        InviteCode old = InviteCode.decode(e.invite);
                        InviteCode fresh = new InviteCode(old.clientId(), old.clientSecret(), refresh, old.folderId(), old.name());
                        // Only this account's folders are reachable with this sign-in; others stay as they were.
                        if (new DriveClient(fresh).getModified(fresh.folderId()) == null) {
                            skipped++;
                            continue;
                        }
                        e.invite = fresh.encode();
                        e.owner = true;
                        updated++;
                    }
                }
                SharedConfig.save();
                status = updated == 0
                        ? "Signed in, but none of the worlds are in that Google account's Drive. Their owner needs to refresh the sign-in."
                        : "Renewed " + updated + " world(s)." + (skipped > 0 ? " " + skipped + " belong to another Google account." : "")
                        + " Friends: paste each world's new invite code into Add world (Edit screen -> Copy).";
            } catch (Exception ex) {
                tscatemfdh.LOGGER.error("Google re-sign-in failed", ex);
                status = "Sign-in failed: " + ex.getMessage();
            }
        });
    }

    // ---- failed saves: write-ahead note on Drive, local copy, recovery ----

    private static final String SESSION_FILE = "session.json";

    private enum Outcome { NONE, UPLOADED, SUPERSEDED, FAILED }

    static String levelIdFor(String folderId) {
        return "tscatemfdh-" + folderId.substring(0, Math.min(10, folderId.length()));
    }

    private static String markerJson(String state, String holder, String uuid, long since, long lastSaved, String base, String note) {
        var o = new com.google.gson.JsonObject();
        o.addProperty("state", state);
        o.addProperty("holder", holder);
        o.addProperty("uuid", uuid);
        o.addProperty("since", since);
        o.addProperty("lastSaved", lastSaved);
        o.addProperty("baseModified", base == null ? "" : base);
        o.addProperty("note", note == null ? "" : note);
        return o.toString();
    }

    /** Best-effort: records on Drive whether this session saved cleanly. Failure to write it is not fatal. */
    private void writeSession(String state, String note) {
        var u = Minecraft.getInstance().getUser();
        String json = markerJson(state, u.getName(), u.getProfileId().toString(), sessionSince, lastSavedAt,
                baseModified == null ? "" : baseModified.toString(), note);
        int tries = "hosting".equals(state) ? 1 : 3; // a lost "clean" would cause a false alarm for the next player
        for (int i = 1; i <= tries; i++) {
            try {
                sync.writeJson(SESSION_FILE, key, json);
                return;
            } catch (Throwable t) {
                tscatemfdh.LOGGER.warn("Could not write the session note to Drive ({}, attempt {})", state, i, t);
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private com.google.gson.JsonObject readSession(WorldSync s, SecretKey k) {
        try {
            String j = s.readJson(SESSION_FILE, k);
            return j == null ? null : com.google.gson.JsonParser.parseString(j).getAsJsonObject();
        } catch (Throwable t) {
            tscatemfdh.LOGGER.warn("Could not read the session note", t);
            return null;
        }
    }

    private void toast(String title, String body) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.getToastManager().addToast(SystemToast.multiline(mc,
                SystemToast.SystemToastId.WORLD_ACCESS_FAILURE, Component.literal(title), Component.literal(body))));
    }

    /** Asks on screen and waits for the answer (the shared worker thread is blocked meanwhile, which is fine here). */
    private boolean askUser(String title, String message, String yes, String no) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        net.minecraft.client.gui.screens.Screen previous = mc.screen;
        mc.execute(() -> mc.setScreen(new ConfirmScreen(answer::complete, Component.literal(title), Component.literal(message),
                Component.literal(yes), Component.literal(no))));
        boolean r = answer.get(15, TimeUnit.MINUTES);
        mc.execute(() -> mc.setScreen(previous));
        return r;
    }

    private static String ago(long ms) {
        long m = Math.max(0, (System.currentTimeMillis() - ms) / 60000);
        if (m < 1) return "less than a minute";
        if (m < 60) return m + " minute" + (m == 1 ? "" : "s");
        long h = m / 60;
        if (h < 48) return h + " hour" + (h == 1 ? "" : "s");
        return (h / 24) + " days";
    }

    /**
     * Called by the player who just won the election, before the world is opened. If the previous session never finished
     * saving, says so and who should have the unsaved progress; the player decides whether to load the last saved version.
     * Answering yes resolves it (the next session note replaces the old one).
     */
    private boolean checkLastSession() throws Exception {
        var m = readSession(sync, key);
        if (m == null || "clean".equals(m.get("state").getAsString())) return true;
        String holder = m.get("holder").getAsString();
        boolean mine = m.get("uuid").getAsString().equals(Minecraft.getInstance().getUser().getProfileId().toString());
        long lastSaved = m.has("lastSaved") ? m.get("lastSaved").getAsLong() : 0;
        String who = mine ? "Your" : holder + "'s";
        String msg = who + " last session on this world didn't finish saving to Drive"
                + (lastSaved > 0 ? " (the last successful save was " + ago(lastSaved) + " before it ended)" : "") + ".\n\n"
                + (mine ? "You may still have the unsaved progress on this computer, in a singleplayer world named something like \"(unsaved)\"."
                : holder + " may still have the unsaved progress on their computer. Ask them to open Minecraft with this mod and the "
                + "world so it uploads.")
                + "\n\nIf you load the last saved version now, that unsaved progress can no longer be added to the shared world.";
        return askUser("The last save didn't finish", msg, "Load last saved version", "Cancel");
    }

    // -- local registry of unsaved sessions --

    private SharedConfig.Pending findPending(String folderId) {
        for (SharedConfig.Pending p : SharedConfig.get().pending) if (p.folderId.equals(folderId)) return p;
        return null;
    }

    private void registerPending() {
        if (entry == null) return;
        SharedConfig.get().pending.removeIf(p -> p.folderId.equals(entry.folderId()));
        SharedConfig.Pending p = new SharedConfig.Pending();
        p.folderId = entry.folderId();
        p.worldName = entry.name;
        p.dir = hostWorldDir.getFileName().toString();
        p.baseModified = baseModified == null ? null : baseModified.toString();
        p.holderUuid = Minecraft.getInstance().getUser().getProfileId().toString();
        p.created = System.currentTimeMillis();
        SharedConfig.get().pending.add(p);
        SharedConfig.save();
    }

    private void clearPending(String folderId) {
        if (folderId == null) return;
        SharedConfig.get().pending.removeIf(p -> p.folderId.equals(folderId));
        SharedConfig.save();
    }

    /** Renames the hosted folder to a visible singleplayer world and points the registry at it. Returns the new name. */
    private String preserveUnsaved(String suffix) {
        Path saves = Minecraft.getInstance().getLevelSource().getBaseDir();
        String folder = preserveAsCopy(hostWorldDir, saves, suffix);
        if (folder == null) return null;
        hostWorldDir = saves.resolve(folder);
        if (entry != null) {
            SharedConfig.Pending p = findPending(entry.folderId());
            if (p != null) {
                p.dir = folder;
                SharedConfig.save();
            }
        }
        return folder;
    }

    /**
     * Finds unsaved progress of mine for this world (registered, or left behind in saves/ by a crashed session) and makes
     * sure it is a normal visible singleplayer world. Returns the registry entry, or null if there is none.
     */
    private SharedConfig.Pending preparePendingFor(SharedConfig.Entry e) {
        try {
            Path saves = Minecraft.getInstance().getLevelSource().getBaseDir();
            SharedConfig.Pending p = findPending(e.folderId());
            Path leftover = saves.resolve(levelIdFor(e.folderId()));
            if (p == null && Files.isDirectory(leftover) && !(hosting && entry != null && entry.folderId().equals(e.folderId()))) {
                // a session that ended without cleaning up: find what it was built on from the note it wrote when it started
                WorldSync s = new WorldSync(new DriveClient(InviteCode.decode(e.invite)));
                var m = readSession(s, resolveKey(e, s));
                String me = Minecraft.getInstance().getUser().getProfileId().toString();
                p = new SharedConfig.Pending();
                p.folderId = e.folderId();
                p.worldName = e.name;
                p.dir = leftover.getFileName().toString();
                p.baseModified = m != null && me.equals(m.get("uuid").getAsString()) && m.has("baseModified")
                        && !m.get("baseModified").getAsString().isEmpty() ? m.get("baseModified").getAsString() : null;
                p.holderUuid = me;
                p.created = System.currentTimeMillis();
                SharedConfig.get().pending.add(p);
                SharedConfig.save();
            }
            if (p != null && p.dir.startsWith("tscatemfdh-") && Files.isDirectory(saves.resolve(p.dir))) {
                String folder = preserveAsCopy(saves.resolve(p.dir), saves, "unsaved");
                if (folder != null) {
                    p.dir = folder;
                    SharedConfig.save();
                }
            }
            return p;
        } catch (Throwable t) {
            tscatemfdh.LOGGER.warn("Could not check for unsaved progress", t);
            return null;
        }
    }

    /**
     * Uploads unsaved progress of mine if nobody has changed the shared world since it was built on; otherwise keeps it
     * as a plain singleplayer world (someone chose to load the last saved version without it).
     */
    private Outcome recoverPending(SharedConfig.Pending p) {
        recoveryLock.lock();
        try {
            SharedConfig.Entry e = SharedConfig.get().findByFolder(p.folderId);
            Path saves = Minecraft.getInstance().getLevelSource().getBaseDir();
            Path dir = saves.resolve(p.dir);
            if (!Files.isDirectory(dir) || e == null) {
                clearPending(p.folderId);
                return Outcome.NONE;
            }
            DriveClient d = new DriveClient(InviteCode.decode(e.invite));
            WorldSync s = new WorldSync(d);
            SecretKey k = resolveKey(e, s);
            if (!sameBase(s, p)) return superseded(p);
            Lease l = new Lease(d, k, Minecraft.getInstance().getUser().getName());
            if (!l.tryAcquire()) {
                l.release();
                status = "Someone else is hosting this world right now, so your unsaved progress can't be added yet.";
                return Outcome.FAILED;
            }
            ScheduledExecutorService hb = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tscatemfdh-recovery-heartbeat");
                t.setDaemon(true);
                return t;
            });
            try {
                hb.scheduleAtFixedRate(() -> {
                    try {
                        l.heartbeat();
                    } catch (Exception ignored) {
                    }
                }, 10, 10, TimeUnit.SECONDS);
                l.purgeStale();
                if (!sameBase(s, p)) return superseded(p); // re-check now that we hold the lease
                s.upload(dir, k);
                var u = Minecraft.getInstance().getUser();
                s.writeJson(SESSION_FILE, k, markerJson("clean", u.getName(), u.getProfileId().toString(), p.created,
                        System.currentTimeMillis(), "", "recovered"));
            } finally {
                hb.shutdownNow();
                l.release();
            }
            clearPending(p.folderId);
            storeInCache(dir, levelIdFor(p.folderId));
            status = "Your unsaved progress for \"" + p.worldName + "\" was uploaded to Drive.";
            toast("Shared world saved", "Your unsaved progress for \"" + p.worldName + "\" was uploaded.");
            return Outcome.UPLOADED;
        } catch (Throwable t) {
            tscatemfdh.LOGGER.warn("Could not upload unsaved progress yet", t);
            status = "Could not upload your unsaved progress yet: " + friendly(t);
            return Outcome.FAILED;
        } finally {
            recoveryLock.unlock();
        }
    }

    private boolean sameBase(WorldSync s, SharedConfig.Pending p) throws Exception {
        java.time.Instant cur = s.worldModified();
        return cur != null && p.baseModified != null && cur.toString().equals(p.baseModified);
    }

    private Outcome superseded(SharedConfig.Pending p) {
        clearPending(p.folderId);
        status = "The shared world \"" + p.worldName + "\" was loaded without your unsaved progress, so it wasn't uploaded. "
                + "It is kept as the singleplayer world \"" + p.dir + "\".";
        toast("Unsaved progress kept locally", "\"" + p.worldName + "\" moved on without it. Your copy: \"" + p.dir + "\".");
        return Outcome.SUPERSEDED;
    }

    /** On opening the multiplayer screen: look for unsaved progress left by earlier sessions and try to upload it. */
    public void recoverLeftovers() {
        if (active) return;
        worker.submit(() -> {
            if (active) return;
            for (SharedConfig.Entry e : new ArrayList<>(SharedConfig.get().worlds)) {
                if (active) return;
                SharedConfig.Pending p = preparePendingFor(e);
                if (p != null) recoverPending(p);
            }
        });
    }
}
