package dev.cd.adrift.utils;

/** Duck interface on {@code LivingEntityRenderState}: the flat tint for a worn skull, set during extract. */
public interface SkullTintState {
    boolean adrift$hasSkullTint();

    int adrift$getSkullTint();

    void adrift$setSkullTint(boolean tinted, int argb);
}
