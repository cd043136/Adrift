package dev.cd.adrift.mixin;

import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The packet only exposes its entity through a level lookup, which fails for entities other mods dropped. */
@Mixin(ClientboundMoveEntityPacket.class)
public interface ClientboundMoveEntityPacketAccessor {

    @Accessor("entityId")
    int adrift$getEntityId();
}
