package net.ethug.tscatemfdh.mixin;

import net.ethug.tscatemfdh.client.SharedSession;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {
    /**
     * A published LAN world forces every player into one game mode. For a shared world each player keeps the game mode
     * they last had (stored in their own player data), so don't force one.
     */
    @Inject(method = "getForcedGameType", at = @At("HEAD"), cancellable = true)
    private void tscatemfdh$keepGameModes(CallbackInfoReturnable<GameType> cir) {
        if (SharedSession.INSTANCE.isHosting()) cir.setReturnValue(null);
    }
}
