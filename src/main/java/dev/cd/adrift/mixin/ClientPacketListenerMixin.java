package dev.cd.adrift.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cd.adrift.events.PacketEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Bundled packets (entity spawns and their data) skip {@link ConnectionMixin}, so they are posted as they are unpacked. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @WrapOperation(
            method = "handleBundlePacket",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V")
    )
    private void adrift$onBundledPacket(Packet<?> packet, PacketListener listener, Operation<Void> original) {
        if (!new PacketEvent.Receive(packet).post()) original.call(packet, listener);
    }
}
