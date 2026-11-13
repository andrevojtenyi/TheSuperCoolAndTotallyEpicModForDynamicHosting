package net.ethug.tscatemfdh.mixin;

import net.ethug.tscatemfdh.client.SharedSession;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In a normal singleplayer/LAN world the host is always an operator when cheats are on. In a shared world whoever
 * hosts is just another player: permissions come only from the operator list (managed from the player list).
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerPermissionMixin {
    @Inject(method = "getProfilePermissions", at = @At("HEAD"), cancellable = true)
    private void tscatemfdh$noAutoOp(NameAndId id, CallbackInfoReturnable<LevelBasedPermissionSet> cir) {
        if ((Object) this instanceof IntegratedServer && SharedSession.INSTANCE.isHosting()) {
            var entry = ((MinecraftServer) (Object) this).getPlayerList().getOps().get(id);
            cir.setReturnValue(entry != null ? entry.permissions() : LevelBasedPermissionSet.ALL);
        }
    }
}
