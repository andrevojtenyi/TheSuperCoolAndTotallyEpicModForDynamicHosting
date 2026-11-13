package net.ethug.tscatemfdh.client;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.server.RegistryLayer;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelDataAndDimensions;

import java.nio.file.Path;
import java.util.Optional;

/** State of the "Share a world" wizard, kept while moving between the form, world picker and world creation. */
public final class ShareDraft {
    public static String worldFolder;      // existing saves/ folder chosen, or null
    public static String worldLabel = "";  // what to show for the chosen/new world
    public static String displayName = "";
    public static String passcode = "";
    public static String confirm = "";

    // A brand-new world whose creation is paused until the form is submitted.
    public static CreateWorldScreen pendingScreen;
    public static LayeredRegistryAccess<RegistryLayer> pendingRegistries;
    public static LevelDataAndDimensions.WorldDataAndGenSettings pendingData;
    public static Optional<GameRules> pendingRules = Optional.empty();
    public static Path pendingPackDir;
    public static Path pendingPackBackup;

    private ShareDraft() {}

    public static boolean hasPendingNewWorld() {
        return pendingScreen != null;
    }

    public static void clearPending() {
        pendingScreen = null;
        pendingRegistries = null;
        pendingData = null;
        pendingRules = Optional.empty();
        pendingPackDir = null;
        pendingPackBackup = null;
    }

    public static void reset() {
        worldFolder = null;
        worldLabel = "";
        displayName = "";
        passcode = "";
        confirm = "";
        clearPending();
    }
}
