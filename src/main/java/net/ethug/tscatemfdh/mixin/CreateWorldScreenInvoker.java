package net.ethug.tscatemfdh.mixin;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.server.RegistryLayer;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;

@Mixin(CreateWorldScreen.class)
public interface CreateWorldScreenInvoker {
    @Invoker("createNewWorld")
    boolean tscatemfdh$createNewWorld(LayeredRegistryAccess<RegistryLayer> registries,
                                      LevelDataAndDimensions.WorldDataAndGenSettings data,
                                      Optional<GameRules> rules);
}
