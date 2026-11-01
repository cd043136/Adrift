package dev.cd.adrift;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Registers mixins that only exist on some Minecraft versions; the shared
 * ones are listed in adrift.mixins.json. Mixin errors on listed classes that
 * don't exist, and Stonecutter comments out the other versions' mixin classes.
 *
 * <p>Lives outside the mixin package on purpose, see {@code RenderDepth}.</p>
 */
public final class AdriftMixinPlugin implements IMixinConfigPlugin {

    @Override
    public List<String> getMixins() {
        List<String> mixins = new ArrayList<>();
        //? if <26.3
        //mixins.add("FeatureRenderDispatcherMixin");
        //? if <26.2
        //mixins.add("OutlineBufferSourceMixin");
        return mixins;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
