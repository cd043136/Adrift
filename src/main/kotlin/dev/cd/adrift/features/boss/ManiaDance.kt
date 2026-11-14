package dev.cd.adrift.features.boss

import com.mojang.blaze3d.vertex.PoseStack
import dev.cd.adrift.features.boss.ManiaFx.Companion.smoothstep
import dev.cd.adrift.utils.SlayerUtils
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.entity.state.HumanoidRenderState
import net.minecraft.world.entity.Entity
import org.joml.Matrix4f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The disco breakdance: the boss's own model is posed through toprock, windmills, a headspin and a freeze in time
 * with the disco beat. Only the render changes; the boss entity, its hitbox and its nametags are left alone.
 *
 * Once a frame [extract] works out the pose from the boss's render state; [rotate] then places the whole body and
 * [pose] bends the limbs of every model drawn for it (body, armour, cape).
 */
object ManiaDance {
    /** Player model height in blocks, after the player renderer's 0.9375 scale. */
    private const val HEIGHT = 1.875
    private const val SHOULDER = 1.4
    /** The dancer is drawn this much bigger; only the render is scaled, never the boss or its hitbox. */
    private const val SCALE = 1.2
    /** Beats either side of a move change spent blending into the next move. */
    private const val CROSSFADE = 0.5
    /** The longest beat step integrated into the spin; longer gaps (a hitch, the boss off screen) don't jump it. */
    private const val MAX_STEP = 0.5

    private const val HEAD = 0
    private const val BODY = 1
    private const val RIGHT_ARM = 2
    private const val LEFT_ARM = 3
    private const val RIGHT_LEG = 4
    private const val LEFT_LEG = 5
    private const val PARTS = 6

    private enum class Move(val beats: Double) { TOPROCK(8.0), WINDMILL(8.0), HEADSPIN(8.0), FREEZE(4.0) }

    private val MOVES = Move.entries
    private val LOOP = MOVES.sumOf { it.beats }

    /** Body placement plus limb rotations (radians, model space) for one instant of the routine. */
    private class Pose {
        val limbs = DoubleArray(PARTS * 3)
        /** Degrees: lean back (90 lies on the back, 180 is upside down), lean sideways, and turn about the spine. */
        var pitch = 0.0
        var tilt = 0.0
        var twist = 0.0
        /** Height up the body (blocks from the feet) that the body turns about and the spin is centred on. */
        var pivot = 0.0
        /** Gap kept under the body's lowest end, and a beat bounce on top. */
        var clearance = 0.0
        var bounce = 0.0
        /** Spin about the vertical, degrees per beat. */
        var spin = 0.0

        fun clear() {
            limbs.fill(0.0)
            pitch = 0.0; tilt = 0.0; twist = 0.0; pivot = 0.0; clearance = 0.0; bounce = 0.0; spin = 0.0
        }

        fun limb(part: Int, x: Double, y: Double, z: Double) {
            limbs[part * 3] = x
            limbs[part * 3 + 1] = y
            limbs[part * 3 + 2] = z
        }

        /** Moves this pose [f] of the way to [other]. */
        fun mix(other: Pose, f: Double) {
            for (i in limbs.indices) limbs[i] += (other.limbs[i] - limbs[i]) * f
            pitch += (other.pitch - pitch) * f
            tilt += (other.tilt - tilt) * f
            twist += wrap(other.twist - twist) * f
            pivot += (other.pivot - pivot) * f
            clearance += (other.clearance - clearance) * f
            bounce += (other.bounce - bounce) * f
            spin += (other.spin - spin) * f
        }
    }

    private val pose = Pose()
    private val other = Pose()
    private val matrix = Matrix4f()

    /** Entity id of the boss being danced this frame, -1 when nobody is. */
    private var dancerId = -1
    /** How far the dance has faded in, 0 to 1. */
    private var weight = 0.0
    private var yaw = 0.0
    private var lastBeat = Double.NaN

    /** Called after the boss's render state is filled in, once a frame. */
    @JvmStatic
    fun extract(entity: Entity) {
        if (entity !== SlayerUtils.boss) return
        val now = System.nanoTime()
        weight = ManiaHelper.danceWeight(now)
        if (weight <= 0.0) {
            dancerId = -1
            lastBeat = Double.NaN
            return
        }
        dancerId = entity.id
        val beat = max(ManiaHelper.danceBeat(now), 0.0)
        routine(beat)
        val step = beat - lastBeat
        if (lastBeat.isNaN()) yaw = 0.0
        else if (step in 0.0..MAX_STEP) yaw = wrap(yaw + pose.spin * step)
        lastBeat = beat
    }

    /**
     * Replaces the renderer's body rotation for the dancer: faces the way the boss does, then lifts, leans and spins
     * the body. Returns false for anyone else, who keeps the vanilla rotation.
     */
    @JvmStatic
    fun rotate(state: AvatarRenderState, poseStack: PoseStack, bodyRot: Float): Boolean {
        if (dancerId < 0 || state.id != dancerId) return false
        val w = weight
        val pitch = pose.pitch * w
        val tilt = pose.tilt * w
        val pivot = pose.pivot * w
        // Lift until the lower end of the spine (feet or head) clears the floor.
        val upright = cos(rad(pitch)) * cos(rad(tilt))
        val lowest = min(-pivot * upright, (HEIGHT - pivot) * upright)
        val lift = (pose.clearance + pose.bounce) * w - lowest
        matrix.identity()
            .rotateY(rad(180.0 - bodyRot + yaw * w).toFloat())
            .scale((1 + (SCALE - 1) * w).toFloat())
            .translate(0f, lift.toFloat(), 0f)
            .rotateZ(rad(tilt).toFloat())
            .rotateX(rad(pitch).toFloat())
            .rotateY(rad(pose.twist * w).toFloat())
            .translate(0f, -pivot.toFloat(), 0f)
        poseStack.mulPose(matrix)
        return true
    }

    /** Bends the limbs of any humanoid model drawn for the dancer, after vanilla has animated it. */
    @JvmStatic
    fun pose(model: HumanoidModel<*>, state: HumanoidRenderState) {
        if (dancerId < 0 || state !is AvatarRenderState || state.id != dancerId) return
        val w = weight
        bend(model.head, HEAD, w)
        bend(model.body, BODY, w)
        bend(model.rightArm, RIGHT_ARM, w)
        bend(model.leftArm, LEFT_ARM, w)
        bend(model.rightLeg, RIGHT_LEG, w)
        bend(model.leftLeg, LEFT_LEG, w)
    }

    private fun bend(part: ModelPart, index: Int, w: Double) {
        val limbs = pose.limbs
        part.xRot += ((limbs[index * 3] - part.xRot) * w).toFloat()
        part.yRot += ((limbs[index * 3 + 1] - part.yRot) * w).toFloat()
        part.zRot += ((limbs[index * 3 + 2] - part.zRot) * w).toFloat()
    }

    /** Fills [pose] for [beat] beats into the dance, blending across move changes. */
    private fun routine(beat: Double) {
        val looped = beat - floor(beat / LOOP) * LOOP
        var index = 0
        var start = 0.0
        while (index < MOVES.size - 1 && looped >= start + MOVES[index].beats) {
            start += MOVES[index].beats
            index++
        }
        val move = MOVES[index]
        val local = looped - start
        perform(move, local, pose)
        val toEnd = move.beats - local
        if (toEnd < CROSSFADE) {
            perform(MOVES[(index + 1) % MOVES.size], -toEnd, other)
            pose.mix(other, smoothstep(CROSSFADE, -CROSSFADE, toEnd))
        } else if (local < CROSSFADE && beat >= CROSSFADE) {
            val previous = MOVES[(index + MOVES.size - 1) % MOVES.size]
            perform(previous, previous.beats + local, other)
            // The current move is already in [pose]; pull it back toward the one it is leaving.
            pose.mix(other, smoothstep(CROSSFADE, -CROSSFADE, local))
        }
    }

    private fun perform(move: Move, x: Double, p: Pose) {
        p.clear()
        when (move) {
            Move.TOPROCK -> toprock(x, p)
            Move.WINDMILL -> windmill(x, p)
            Move.HEADSPIN -> headspin(x, p)
            Move.FREEZE -> freeze(x, p)
        }
    }

    /** Upright stepping: a foot kicks across on each beat, the opposite arm swings up and the body sways. */
    private fun toprock(x: Double, p: Pose) {
        val s = sin(PI * x)
        val kick = abs(s)
        val right = max(s, 0.0)
        val left = max(-s, 0.0)
        p.bounce = 0.07 * kick
        p.twist = 18 * s
        p.limb(HEAD, 0.15 * kick, -0.25 * s, 0.0)
        p.limb(RIGHT_LEG, -0.7 * right, 0.35 * right, 0.08)
        p.limb(LEFT_LEG, -0.7 * left, -0.35 * left, -0.08)
        p.limb(RIGHT_ARM, -0.5 - 1.0 * left, 0.0, 0.3 + 0.2 * left)
        p.limb(LEFT_ARM, -0.5 - 1.0 * right, 0.0, -0.3 - 0.2 * right)
    }

    /** On the back, rolling over the shoulders while the body circles, legs scissoring wide. */
    private fun windmill(x: Double, p: Pose) {
        val s = sin(PI * x)
        val spread = 1.0 + 0.25 * s
        p.pitch = 90.0
        p.pivot = SHOULDER
        p.clearance = 0.25
        p.twist = wrap(180 * x)
        p.spin = 180.0
        p.limb(HEAD, 0.5, 0.0, 0.0)
        p.limb(RIGHT_LEG, -0.25 * s, 0.0, spread)
        p.limb(LEFT_LEG, 0.25 * s, 0.0, -spread)
        p.limb(RIGHT_ARM, -1.3, 0.0, -0.35)
        p.limb(LEFT_ARM, -1.3, 0.0, 0.35)
    }

    /** Upside down on the head, spinning up to speed with legs in a V and arms out. */
    private fun headspin(x: Double, p: Pose) {
        val sway = 0.25 * sin(PI * x / 2)
        p.pitch = 180.0
        p.pivot = HEIGHT
        p.clearance = 0.02
        p.spin = 360 * (0.5 + 0.5 * smoothstep(0.0, 3.0, x))
        p.limb(RIGHT_LEG, sway, 0.0, 0.6)
        p.limb(LEFT_LEG, -sway, 0.0, -0.6)
        p.limb(RIGHT_ARM, 0.0, 0.0, 1.3)
        p.limb(LEFT_ARM, 0.0, 0.0, -1.3)
    }

    /** Held still, tipped over on the head and hands with one leg tucked. */
    @Suppress("UNUSED_PARAMETER")
    private fun freeze(x: Double, p: Pose) {
        p.pitch = 160.0
        p.tilt = 35.0
        p.twist = 30.0
        p.pivot = 1.65
        p.clearance = 0.05
        p.limb(HEAD, -0.3, 0.0, 0.0)
        p.limb(RIGHT_LEG, -1.5, 0.0, 0.3)
        p.limb(LEFT_LEG, -0.3, 0.0, -0.6)
        p.limb(RIGHT_ARM, -2.9, 0.0, 0.3)
        p.limb(LEFT_ARM, -2.9, 0.0, -0.3)
    }

    private fun rad(degrees: Double) = degrees * (PI / 180)

    /** Degrees wrapped to -180..180. */
    private fun wrap(degrees: Double): Double = degrees - 360 * floor((degrees + 180) / 360)
}
