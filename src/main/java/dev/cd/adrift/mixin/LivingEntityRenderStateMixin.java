package dev.cd.adrift.mixin;

import dev.cd.adrift.utils.SkullTintState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Carries the Resurrect Alert skull tint from extract to the head layer. */
@Mixin(LivingEntityRenderState.class)
public abstract class LivingEntityRenderStateMixin implements SkullTintState {

    @Unique
    private boolean adrift$skullTinted;

    @Unique
    private int adrift$skullTint;

    @Override
    public boolean adrift$hasSkullTint() {
        return this.adrift$skullTinted;
    }

    @Override
    public int adrift$getSkullTint() {
        return this.adrift$skullTint;
    }

    @Override
    public void adrift$setSkullTint(boolean tinted, int argb) {
        this.adrift$skullTinted = tinted;
        this.adrift$skullTint = argb;
    }
}
