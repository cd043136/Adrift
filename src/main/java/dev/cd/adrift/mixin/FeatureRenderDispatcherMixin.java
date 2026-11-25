package dev.cd.adrift.mixin;

// Not needed on 26.3+, where LevelRendererMixin attaches scene depth to the outline pass instead.
//? if <26.3 {
/*import dev.cd.adrift.utils.RenderDepth;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//? if >=26.2 {
/^*
 * Copies scene depth into the outline target before the outline pass.
 * The outline pass runs after all solid and translucent features, so `main`
 * depth is complete by then and includes entities as well as terrain.
 ^/
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public abstract class FeatureRenderDispatcherMixin {

    @Inject(method = "executeOutline", at = @At("HEAD"))
    private void adrift$copySceneDepth(CallbackInfo ci) {
        RenderDepth.copySceneDepth();
    }
}
//?} else {
/^/^¹*
 * Seeds outline depth before solid draws flush.
 * Runs after opaque terrain is already in `main` depth but before any solid
 * feature (including our textured outline first pass) draws, so walls
 * occlude glow even though entities are not in the copied depth yet.
 * Only the world pass calls renderSolidFeatures directly; the hand, GUI
 * items and entity previews go through renderAllFeatures and are skipped.
 ¹^/
@Mixin(FeatureRenderDispatcher.class)
public abstract class FeatureRenderDispatcherMixin {

    @Unique
    private boolean adrift$inRenderAll;

    @Inject(method = "renderAllFeatures", at = @At("HEAD"))
    private void adrift$enterRenderAll(CallbackInfo ci) {
        this.adrift$inRenderAll = true;
    }

    @Inject(method = "renderAllFeatures", at = @At("RETURN"))
    private void adrift$exitRenderAll(CallbackInfo ci) {
        this.adrift$inRenderAll = false;
    }

    @Inject(method = "renderSolidFeatures", at = @At("HEAD"))
    private void adrift$seedOutlineDepth(CallbackInfo ci) {
        if (!this.adrift$inRenderAll) RenderDepth.copySceneDepth();
    }
}
^///?}
*///?}
