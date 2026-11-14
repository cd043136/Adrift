package dev.cd.adrift.features.boss

import com.mojang.blaze3d.vertex.PoseStack
import dev.cd.adrift.features.boss.ManiaFx.Companion.TAU
import dev.cd.adrift.features.boss.ManiaFx.Companion.fraction
import dev.cd.adrift.features.boss.ManiaFx.Companion.hash
import dev.cd.adrift.features.boss.ManiaFx.Companion.smoothstep
import dev.cd.adrift.utils.Color
import dev.cd.adrift.utils.Colors
import dev.cd.adrift.utils.SlayerUtils
import dev.cd.adrift.utils.render.GeometryBatch
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.entity.state.HumanoidRenderState
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The disco breakdance: the boss's own model is posed through an extravagant 48-beat B-boy showcase — toprock, 6-step
 * footwork, barrel windmills, airflares, 1990 handhops, a turbo headspin, hollowback freeze and a backflip corkscrew
 * finale — locked to the disco beat, complete with additive neon limb ribbons, cypher spotlight, beat rings and
 * contact sparks.
 *
 * Strictly visual-only: only the client render matrices, model limb angles and additive glow batch are touched. The
 * boss entity, its position, its bounding box / hitbox and its nametags are never modified.
 *
 * Once a frame [extract] works out the pose from the boss's render state; [rotate] then places the whole body, [pose]
 * bends the limbs of every model drawn for it (body, armour, cape), and [drawFx] emits the stage effects.
 */
object ManiaDance {
    /** Player model height in blocks, after the player renderer's 0.9375 scale. */
    private const val HEIGHT = 1.875
    private const val MODEL_SCALE = 0.9375
    private const val HIP = 0.75
    private const val WAIST = 1.05
    private const val SHOULDER = 1.4
    /** Base visual scale of the dancer; only the render PoseStack is scaled, never the entity or its hitbox. */
    private const val SCALE = 1.22
    /** Beats either side of a move change spent blending into the next move. */
    private const val CROSSFADE = 0.45
    /** The longest beat step integrated into the spin; longer gaps (a hitch, the boss off screen) don't jump it. */
    private const val MAX_STEP = 0.5

    private const val HEAD = 0
    private const val BODY = 1
    private const val RIGHT_ARM = 2
    private const val LEFT_ARM = 3
    private const val RIGHT_LEG = 4
    private const val LEFT_LEG = 5
    private const val PARTS = 6

    private const val TRAIL_MAX = 48
    private const val TRAIL_SPAN = 1.65
    private const val TRAIL_MIN_STEP = 0.025
    private const val SPARKS = 14
    private const val RING_SEGMENTS = 32
    private const val RING_BEATS = 1.35

    /** 48-beat showcase (~22.5s at 128 BPM) covering an entire mania phase with a grand finale before fade-out. */
    private enum class Move(val beats: Double) {
        TOPROCK(6.0),
        SIX_STEP(6.0),
        WINDMILL(8.0),
        AIRFLARE(8.0),
        HANDHOP_1990(6.0),
        HEADSPIN(6.0),
        HOLLOWBACK_FREEZE(4.0),
        CORKSCREW_FINALE(4.0),
    }

    private val MOVES = Move.entries
    private val LOOP = MOVES.sumOf { it.beats }

    /** Neon stage palette — intentionally omits green so green on the floor exclusively means safe. */
    private val NEON = arrayOf(
        Color(0xFFFF2DD2.toInt()), // hot magenta
        Color(0xFF2DE1FF.toInt()), // electric cyan
        Color(0xFFFFC83C.toInt()), // solar gold
        Color(0xFF9B5BFF.toInt()), // ultraviolet
        Color(0xFFFF5A8C.toInt()), // coral neon
        Color(0xFF3C78FF.toInt()), // cobalt laser
    )

    /** Body placement plus limb rotations (radians, model space) and visual FX intensities for one instant. */
    private class Pose {
        val limbs = DoubleArray(PARTS * 3)
        /** Degrees: lean back (90 lies on the back, 180 is upside down), lean sideways, and turn about the spine. */
        var pitch = 0.0
        var tilt = 0.0
        var twist = 0.0
        /** Height up the body (blocks from the feet) that the body turns about and the spin is centred on. */
        var pivot = 0.0
        /** Gap kept under the body's lowest end, and a beat/jump lift on top. */
        var clearance = 0.0
        var bounce = 0.0
        /** Visual horizontal cypher orbit offset in blocks (render-only). */
        var swayX = 0.0
        var swayZ = 0.0
        /** Spin about the vertical, degrees per beat. */
        var spin = 0.0
        /** Beat-driven squash-and-stretch pop added to the render scale. */
        var pop = 0.0
        /** 0..1 intensity of neon limb trails, floor contact sparks, and impact shockwave pulse. */
        var flare = 0.0
        var sparks = 0.0
        var impact = 0.0

        fun clear() {
            limbs.fill(0.0)
            pitch = 0.0; tilt = 0.0; twist = 0.0
            pivot = 0.0; clearance = 0.0; bounce = 0.0
            swayX = 0.0; swayZ = 0.0; spin = 0.0
            pop = 0.0; flare = 0.0; sparks = 0.0; impact = 0.0
        }

        fun limb(part: Int, x: Double, y: Double, z: Double) {
            limbs[part * 3] = x
            limbs[part * 3 + 1] = y
            limbs[part * 3 + 2] = z
        }

        /** Moves this pose [f] of the way to [other], taking shortest angular paths on wrapped body orientations. */
        fun mix(other: Pose, f: Double) {
            for (i in limbs.indices) limbs[i] += (other.limbs[i] - limbs[i]) * f
            pitch += wrap(other.pitch - pitch) * f
            tilt += wrap(other.tilt - tilt) * f
            twist += wrap(other.twist - twist) * f
            pivot += (other.pivot - pivot) * f
            clearance += (other.clearance - clearance) * f
            bounce += (other.bounce - bounce) * f
            swayX += (other.swayX - swayX) * f
            swayZ += (other.swayZ - swayZ) * f
            spin += (other.spin - spin) * f
            pop += (other.pop - pop) * f
            flare += (other.flare - flare) * f
            sparks += (other.sparks - sparks) * f
            impact += (other.impact - impact) * f
        }
    }

    private val pose = Pose()
    private val other = Pose()
    private val matrix = Matrix4f()
    private val localMatrix = Matrix4f()
    private val poseQuat = Quaternionf()
    private val targetQuat = Quaternionf()
    private val limbQuat = Quaternionf()
    private val scratchVec = Vector3f()
    private val scratchEuler = Vector3f()

    /** Entity id of the boss being danced this frame, -1 when nobody is. */
    private var dancerId = -1
    /** How far the dance has faded in, 0 to 1. */
    private var weight = 0.0
    private var yaw = 0.0
    private var lastBeat = Double.NaN
    private var currentBeat = 0.0
    private var currentMove = 0

    // Interpolated render-only origin and facing captured from AvatarRenderState during extract.
    private var renderX = 0.0
    private var renderY = 0.0
    private var renderZ = 0.0
    private var renderBodyRot = 0f
    private var renderEntityScale = 1f
    private var contactX = 0.0
    private var contactZ = 0.0
    private var impactBeat = -100.0
    private var prevImpact = 0.0

    // Circular history buffer of world-space limb tips (right foot, left foot, right hand, left hand) for neon ribbons.
    private val trailBeats = DoubleArray(TRAIL_MAX)
    private val trailFlare = DoubleArray(TRAIL_MAX)
    private val trailTips = DoubleArray(TRAIL_MAX * 4 * 3)
    private var trailHead = 0
    private var trailCount = 0

    /** Called after the boss's render state is filled in, once a frame. Never touches [entity] fields. */
    @JvmStatic
    fun extract(entity: Entity, state: AvatarRenderState) {
        if (entity !== SlayerUtils.boss) return
        val now = System.nanoTime()
        weight = ManiaHelper.danceWeight(now)
        if (weight <= 0.0) {
            dancerId = -1
            lastBeat = Double.NaN
            trailCount = 0
            impactBeat = -100.0
            prevImpact = 0.0
            return
        }
        dancerId = state.id
        renderX = state.x
        renderY = state.y
        renderZ = state.z
        renderBodyRot = state.bodyRot
        renderEntityScale = state.scale

        val beat = max(ManiaHelper.danceBeat(now), 0.0)
        val step = beat - lastBeat
        if (lastBeat.isNaN() || step < 0.0 || step > MAX_STEP * 4) {
            yaw = 0.0
            trailCount = 0
            impactBeat = -100.0
            prevImpact = 0.0
        }
        routine(beat)
        if (!lastBeat.isNaN() && step in 0.0..MAX_STEP) {
            yaw = wrap(yaw + pose.spin * step)
        }
        currentBeat = beat
        lastBeat = beat

        if (pose.impact > 0.55 && prevImpact <= 0.55 && beat - impactBeat > 0.6) {
            impactBeat = beat
        }
        prevImpact = pose.impact

        captureKinematics(beat)
    }

    /**
     * Replaces the renderer's body rotation for the dancer: faces the way the boss does, shifts along the cypher,
     * scales with beat pop, then lifts, leans and spins the body. Returns false for anyone else.
     */
    @JvmStatic
    fun rotate(state: AvatarRenderState, poseStack: PoseStack, bodyRot: Float): Boolean {
        if (dancerId < 0 || state.id != dancerId) return false
        buildBodyMatrix(bodyRot, weight, matrix)
        poseStack.mulPose(matrix)
        return true
    }

    private fun buildBodyMatrix(bodyRot: Float, w: Double, out: Matrix4f) {
        val pitch = pose.pitch * w
        val tilt = pose.tilt * w
        val pivot = pose.pivot * w
        // Lift until the lower end of the spine (feet, shoulders or head) clears the floor.
        val upright = cos(rad(pitch)) * cos(rad(tilt))
        val lowest = min(-pivot * upright, (HEIGHT - pivot) * upright)
        val lift = (pose.clearance + pose.bounce) * w - lowest
        val dynScale = 1.0 + (SCALE * (1.0 + pose.pop) - 1.0) * w
        val baseYaw = rad(180.0 - bodyRot)
        val cosBase = cos(baseYaw)
        val sinBase = sin(baseYaw)
        val wx = (pose.swayX * cosBase + pose.swayZ * sinBase) * w
        val wz = (-pose.swayX * sinBase + pose.swayZ * cosBase) * w

        out.identity()
            .translate(wx.toFloat(), 0f, wz.toFloat())
            .rotateY(rad(180.0 - bodyRot + yaw * w).toFloat())
            .scale(dynScale.toFloat())
            .translate(0f, lift.toFloat(), 0f)
            .rotateZ(rad(tilt).toFloat())
            .rotateX(rad(pitch).toFloat())
            .rotateY(rad(pose.twist * w).toFloat())
            .translate(0f, -pivot.toFloat(), 0f)
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
        // Stabilise torso/arm/head translational offsets relative to initialPose if vanilla had the boss crouching.
        val keep = (1.0 - w).toFloat()
        resetOffset(model.head, keep)
        resetOffset(model.body, keep)
        resetOffset(model.rightArm, keep)
        resetOffset(model.leftArm, keep)
        resetOffset(model.rightLeg, keep)
        resetOffset(model.leftLeg, keep)
    }

    private fun resetOffset(part: ModelPart, keep: Float) {
        val init = part.initialPose
        part.x = init.x() + (part.x - init.x()) * keep
        part.y = init.y() + (part.y - init.y()) * keep
        part.z = init.z() + (part.z - init.z()) * keep
    }

    /**
     * Blends [part]'s rotation toward the routine's target via spherical quaternion interpolation (`slerp`), preventing
     * 2π angle-wrap snapping against vanilla's item-use / walking animations during fade-in and fade-out.
     */
    private fun bend(part: ModelPart, index: Int, w: Double) {
        val limbs = pose.limbs
        val tx = limbs[index * 3].toFloat()
        val ty = limbs[index * 3 + 1].toFloat()
        val tz = limbs[index * 3 + 2].toFloat()
        if (w >= 0.999) {
            part.xRot = tx
            part.yRot = ty
            part.zRot = tz
            return
        }
        poseQuat.rotationZYX(part.zRot, part.yRot, part.xRot)
        targetQuat.rotationZYX(tz, ty, tx)
        poseQuat.slerp(targetQuat, w.toFloat())
        poseQuat.getEulerAnglesZYX(scratchEuler)
        part.xRot = scratchEuler.x
        part.yRot = scratchEuler.y
        part.zRot = scratchEuler.z
    }

    internal fun hasFx(): Boolean = dancerId >= 0 && weight > 0.01

    /**
     * Draws additive stage effects around the breakdancing boss: a pulsing cypher spotlight & beat ring on the floor,
     * expanding impact shockwaves on freezes/landings, neon ribbon trails off the hands and feet, and floor sparks.
     */
    internal fun drawFx(batch: GeometryBatch, camera: Vec3) {
        if (!hasFx()) return
        val w = weight
        val beat = currentBeat
        val bar = floor(beat / 4.0).toInt()
        val kick = exp(-fraction(beat) * 5.5)
        val primary = NEON[(currentMove + bar) % NEON.size]
        val secondary = NEON[(currentMove + bar + 1) % NEON.size]
        val accent = NEON[(currentMove + bar + 2) % NEON.size]

        drawCypherFloor(batch, w, beat, kick, primary, secondary, accent)
        drawTrails(batch, camera, w, beat, primary, secondary)
        if (pose.sparks * w > 0.04) {
            drawSparks(batch, camera, w, beat, accent)
        }
    }

    private fun drawCypherFloor(
        batch: GeometryBatch,
        w: Double,
        beat: Double,
        kick: Double,
        primary: Color,
        secondary: Color,
        accent: Color,
    ) {
        val y = renderY + 0.014
        val cx = contactX
        val cz = contactZ

        // Soft glowing spotlight pool under the dancer's centre of mass + spinning beat ring.
        val poolRadius = 1.15 + 0.28 * kick + 0.18 * pose.flare
        val poolAlpha = ((0.18 + 0.14 * kick) * w * 255).roundToInt().coerceIn(0, 255)
        val poolCore = (poolAlpha shl 24) or primary.mix(Colors.WHITE, 0.45).rgb
        val poolEdge = primary.rgb

        val ringInner = poolRadius * 0.78
        val ringOuter = ringInner + 0.11 + 0.06 * kick
        val spinAngle = beat * 0.85

        for (s in 0 until RING_SEGMENTS) {
            val a0 = TAU * s / RING_SEGMENTS + spinAngle
            val a1 = TAU * (s + 1) / RING_SEGMENTS + spinAngle
            val c0 = cos(a0); val s0 = sin(a0)
            val c1 = cos(a1); val s1 = sin(a1)

            // Soft spotlight fan triangle (degenerate quad at centre).
            batch.shadedQuad(
                cx, y, cz, poolCore,
                cx + c0 * poolRadius, y, cz + s0 * poolRadius, poolEdge,
                cx + c1 * poolRadius, y, cz + s1 * poolRadius, poolEdge,
                cx, y, cz, poolCore,
            )

            // Chasing neon cypher ring around the dancer.
            val chase = 0.45 + 0.55 * sin(a0 * 3.0 - beat * PI)
            val ringCol = if (s % 2 == 0) primary else secondary
            val rAlpha = ((0.22 + 0.32 * chase * (0.6 + 0.4 * kick)) * w * 255).roundToInt().coerceIn(0, 255)
            val lit = (rAlpha shl 24) or ringCol.mix(Colors.WHITE, 0.35 * chase).rgb
            val dim = ringCol.rgb
            val midR = (ringInner + ringOuter) * 0.5
            batch.shadedQuad(
                cx + c0 * ringInner, y + 0.002, cz + s0 * ringInner, dim,
                cx + c0 * midR, y + 0.002, cz + s0 * midR, lit,
                cx + c1 * midR, y + 0.002, cz + s1 * midR, lit,
                cx + c1 * ringInner, y + 0.002, cz + s1 * ringInner, dim,
            )
            batch.shadedQuad(
                cx + c0 * midR, y + 0.002, cz + s0 * midR, lit,
                cx + c0 * ringOuter, y + 0.002, cz + s0 * ringOuter, dim,
                cx + c1 * ringOuter, y + 0.002, cz + s1 * ringOuter, dim,
                cx + c1 * midR, y + 0.002, cz + s1 * midR, lit,
            )
        }

        // Expanding shockwave burst when hitting a freeze or landing the finale flip.
        val sinceImpact = beat - impactBeat
        if (sinceImpact in 0.0..RING_BEATS) {
            val t = sinceImpact / RING_BEATS
            val fade = (1.0 - t).pow(2) * w
            val radius = 0.35 + 2.65 * (1.0 - (1.0 - t).pow(2.2))
            val halfWidth = 0.16 * (1.0 - 0.45 * t)
            val burstRgb = accent.mix(Colors.WHITE, 0.6).rgb
            val burstLit = ((0.72 * fade * 255).roundToInt().coerceIn(0, 255) shl 24) or burstRgb
            for (s in 0 until RING_SEGMENTS) {
                val a0 = TAU * s / RING_SEGMENTS
                val a1 = TAU * (s + 1) / RING_SEGMENTS
                val c0 = cos(a0); val s0 = sin(a0)
                val c1 = cos(a1); val s1 = sin(a1)
                val rIn = max(0.02, radius - halfWidth)
                val rOut = radius + halfWidth
                batch.shadedQuad(
                    cx + c0 * rIn, y + 0.005, cz + s0 * rIn, burstRgb,
                    cx + c0 * radius, y + 0.005, cz + s0 * radius, burstLit,
                    cx + c1 * radius, y + 0.005, cz + s1 * radius, burstLit,
                    cx + c1 * rIn, y + 0.005, cz + s1 * rIn, burstRgb,
                )
                batch.shadedQuad(
                    cx + c0 * radius, y + 0.005, cz + s0 * radius, burstLit,
                    cx + c0 * rOut, y + 0.005, cz + s0 * rOut, burstRgb,
                    cx + c1 * rOut, y + 0.005, cz + s1 * rOut, burstRgb,
                    cx + c1 * radius, y + 0.005, cz + s1 * radius, burstLit,
                )
            }
        }
    }

    private fun drawTrails(
        batch: GeometryBatch,
        camera: Vec3,
        w: Double,
        beat: Double,
        primary: Color,
        secondary: Color,
    ) {
        if (trailCount < 2) return
        for (limb in 0 until 4) {
            val baseCol = if (limb < 2) primary else secondary
            val hotRgb = baseCol.mix(Colors.WHITE, 0.72).rgb
            val baseRgb = baseCol.rgb
            val baseWidth = if (limb < 2) 0.115 else 0.09

            var prevIdx = -1
            var prevNx = 0.0; var prevNy = 0.0; var prevNz = 0.0
            var prevCore = 0; var prevEdge = 0; var prevHalf = 0.0

            for (k in 0 until trailCount) {
                val idx = (trailHead - trailCount + k + TRAIL_MAX) % TRAIL_MAX
                val age = beat - trailBeats[idx]
                if (age < 0.0 || age > TRAIL_SPAN) {
                    prevIdx = -1
                    continue
                }
                val life = (1.0 - age / TRAIL_SPAN).pow(1.65)
                val flare = trailFlare[idx] * life * w
                val o = (idx * 4 + limb) * 3
                val x = trailTips[o]
                val y = trailTips[o + 1]
                val z = trailTips[o + 2]

                if (prevIdx >= 0 && flare > 0.015) {
                    val po = (prevIdx * 4 + limb) * 3
                    val px = trailTips[po]
                    val py = trailTips[po + 1]
                    val pz = trailTips[po + 2]
                    val dx = x - px
                    val dy = y - py
                    val dz = z - pz
                    val segLenSq = dx * dx + dy * dy + dz * dz
                    if (segLenSq in 1.0e-5..1.4) {
                        val vx = camera.x - x
                        val vy = camera.y - y
                        val vz = camera.z - z
                        var nx = dy * vz - dz * vy
                        var ny = dz * vx - dx * vz
                        var nz = dx * vy - dy * vx
                        val nLen = sqrt(nx * nx + ny * ny + nz * nz)
                        if (nLen > 1.0e-6) {
                            nx /= nLen; ny /= nLen; nz /= nLen
                            val half = baseWidth * (0.3 + 0.7 * life)
                            val coreAlpha = ((0.68 * flare) * 255).roundToInt().coerceIn(0, 255)
                            val core = (coreAlpha shl 24) or hotRgb
                            val edge = baseRgb
                            if (prevHalf > 0.0) {
                                // Two ribbon halves meeting along a hot white-neon spine for a soft volumetric glow.
                                batch.shadedQuad(
                                    px - prevNx * prevHalf, py - prevNy * prevHalf, pz - prevNz * prevHalf, prevEdge,
                                    px, py, pz, prevCore,
                                    x, y, z, core,
                                    x - nx * half, y - ny * half, z - nz * half, edge,
                                )
                                batch.shadedQuad(
                                    px, py, pz, prevCore,
                                    px + prevNx * prevHalf, py + prevNy * prevHalf, pz + prevNz * prevHalf, prevEdge,
                                    x + nx * half, y + ny * half, z + nz * half, edge,
                                    x, y, z, core,
                                )
                            }
                            prevNx = nx; prevNy = ny; prevNz = nz
                            prevCore = core; prevEdge = edge; prevHalf = half
                        }
                    } else {
                        prevHalf = 0.0
                    }
                } else {
                    prevHalf = 0.0
                }
                prevIdx = idx
            }
        }
    }

    private fun drawSparks(
        batch: GeometryBatch,
        camera: Vec3,
        w: Double,
        beat: Double,
        accent: Color,
    ) {
        val power = (pose.sparks * w).coerceIn(0.0, 1.0)
        val spinDir = if (pose.spin >= 0.0) 1.0 else -1.0
        val baseY = renderY + 0.02
        for (i in 0 until SPARKS) {
            val rate = 2.4 + 1.5 * hash(i, 1, 71)
            val clock = beat * rate + hash(i, 2, 73)
            val slot = floor(clock).toInt()
            val t = clock - slot
            val angle = TAU * hash(i, slot, 79) + spinDir * t * 1.15
            val speed = 0.75 + 1.45 * hash(i, slot, 83)
            val liftPeak = 0.25 + 0.55 * hash(i, slot, 89)

            val tTail = max(0.0, t - 0.16)
            val rHead = 0.14 + speed * t
            val rTail = 0.14 + speed * tTail
            val cHead = cos(angle); val sHead = sin(angle)
            val angleTail = angle - spinDir * (t - tTail) * 1.15
            val cTail = cos(angleTail); val sTail = sin(angleTail)

            val hx = contactX + cHead * rHead
            val hy = baseY + liftPeak * 4.0 * t * (1.0 - t)
            val hz = contactZ + sHead * rHead
            val tx = contactX + cTail * rTail
            val ty = baseY + liftPeak * 4.0 * tTail * (1.0 - tTail)
            val tz = contactZ + sTail * rTail

            val dx = hx - tx; val dy = hy - ty; val dz = hz - tz
            val vx = camera.x - hx; val vy = camera.y - hy; val vz = camera.z - hz
            var nx = dy * vz - dz * vy
            var ny = dz * vx - dx * vz
            var nz = dx * vy - dy * vx
            val nLen = sqrt(nx * nx + ny * ny + nz * nz)
            if (nLen < 1.0e-6) continue
            nx /= nLen; ny /= nLen; nz /= nLen

            val fade = (1.0 - t).pow(1.4) * power
            val sparkCol = if (i % 3 == 0) Colors.WHITE else accent.mix(Colors.WHITE, 0.55)
            val headCol = ((0.9 * fade * 255).roundToInt().coerceIn(0, 255) shl 24) or sparkCol.rgb
            val tailCol = sparkCol.rgb
            val half = 0.032 * (0.5 + 0.5 * fade)

            batch.shadedQuad(
                tx - nx * half * 0.4, ty - ny * half * 0.4, tz - nz * half * 0.4, tailCol,
                tx + nx * half * 0.4, ty + ny * half * 0.4, tz + nz * half * 0.4, tailCol,
                hx + nx * half, hy + ny * half, hz + nz * half, headCol,
                hx - nx * half, hy - ny * half, hz - nz * half, headCol,
            )
        }
    }

    /**
     * Exact forward kinematics matching `LivingEntityRenderer.submit` + `ModelPart.translateAndRotate` so ribbon trails
     * lock onto the dancer's actual rendered hands and feet without touching any entity state.
     */
    private fun captureKinematics(beat: Double) {
        val w = weight
        val es = renderEntityScale
        matrix.identity()
            .translate(renderX.toFloat(), renderY.toFloat(), renderZ.toFloat())
            .scale(es, es, es)
        buildBodyMatrix(renderBodyRot, w, localMatrix)
        matrix.mul(localMatrix)
        // Track the dancer's floor contact point directly beneath the posed pivot.
        scratchVec.set(0f, pose.pivot.toFloat() * w.toFloat(), 0f)
        matrix.transformPosition(scratchVec)
        contactX = scratchVec.x.toDouble()
        contactZ = scratchVec.z.toDouble()

        if (pose.flare * w <= 0.02) return
        if (trailCount > 0) {
            val prevIdx = (trailHead - 1 + TRAIL_MAX) % TRAIL_MAX
            if (beat - trailBeats[prevIdx] < TRAIL_MIN_STEP) return
        }

        // Convert into HumanoidModel root space: scale(-1, -1, 1) -> scale(0.9375) -> translate(0, -1.501, 0).
        val ms = MODEL_SCALE.toFloat()
        matrix.scale(-ms, -ms, ms).translate(0f, -1.501f, 0f)

        val slot = trailHead
        trailBeats[slot] = beat
        trailFlare[slot] = pose.flare
        // Right & left feet (pivot at (-1.9, 12, 0) / (1.9, 12, 0) px, tip 12 px down the leg).
        limbTip(slot, 0, RIGHT_LEG, -1.9f, 12f, 0f, 0f, 12f, 0f, w)
        limbTip(slot, 1, LEFT_LEG, 1.9f, 12f, 0f, 0f, 12f, 0f, w)
        // Right & left hands (pivot at (-5, 2, 0) / (5, 2, 0) px, tip 10 px down from shoulder pivot).
        limbTip(slot, 2, RIGHT_ARM, -5f, 2f, 0f, -1f, 10f, 0f, w)
        limbTip(slot, 3, LEFT_ARM, 5f, 2f, 0f, 1f, 10f, 0f, w)

        trailHead = (trailHead + 1) % TRAIL_MAX
        if (trailCount < TRAIL_MAX) trailCount++
    }

    private fun limbTip(
        slot: Int,
        limbIdx: Int,
        part: Int,
        px: Float, py: Float, pz: Float,
        tx: Float, ty: Float, tz: Float,
        w: Double,
    ) {
        val rx = (pose.limbs[part * 3] * w).toFloat()
        val ry = (pose.limbs[part * 3 + 1] * w).toFloat()
        val rz = (pose.limbs[part * 3 + 2] * w).toFloat()
        limbQuat.rotationZYX(rz, ry, rx)
        scratchVec.set(tx / 16f, ty / 16f, tz / 16f)
        limbQuat.transform(scratchVec)
        scratchVec.add(px / 16f, py / 16f, pz / 16f)
        matrix.transformPosition(scratchVec)
        val o = (slot * 4 + limbIdx) * 3
        trailTips[o] = scratchVec.x.toDouble()
        trailTips[o + 1] = scratchVec.y.toDouble()
        trailTips[o + 2] = scratchVec.z.toDouble()
    }

    /** Fills [pose] for [beat] beats into the dance, blending smoothly across move transitions. */
    private fun routine(beat: Double) {
        val looped = beat - floor(beat / LOOP) * LOOP
        var index = 0
        var start = 0.0
        while (index < MOVES.size - 1 && looped >= start + MOVES[index].beats) {
            start += MOVES[index].beats
            index++
        }
        currentMove = index
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
            pose.mix(other, smoothstep(CROSSFADE, -CROSSFADE, local))
        }
    }

    private fun perform(move: Move, x: Double, p: Pose) {
        p.clear()
        when (move) {
            Move.TOPROCK -> toprock(x, p)
            Move.SIX_STEP -> sixStep(x, p)
            Move.WINDMILL -> windmill(x, p)
            Move.AIRFLARE -> airflare(x, p)
            Move.HANDHOP_1990 -> handhop1990(x, p)
            Move.HEADSPIN -> headspin(x, p)
            Move.HOLLOWBACK_FREEZE -> hollowbackFreeze(x, p)
            Move.CORKSCREW_FINALE -> corkscrewFinale(x, p)
        }
    }

    /**
     * Indian-step / Salsa-rock toprock with upbeat arm opens, cross-step kicks, cypher groove sway and a 360° spin-down
     * drop on beats 4..6 into floor footwork.
     */
    private fun toprock(x: Double, p: Pose) {
        val s = sin(PI * x)
        val c = cos(PI * x)
        val kick = abs(s)
        val groove = beatPulse(x, 6.0)
        val right = max(s, 0.0)
        val left = max(-s, 0.0)
        val drop = smoothstep(4.3, 5.9, x)

        p.pivot = HIP * drop
        p.pitch = -10.0 * kick * (1.0 - drop) + 38.0 * drop
        p.tilt = 9.0 * s * (1.0 - 0.5 * drop)
        p.twist = 26.0 * s * (1.0 - 0.4 * drop)
        p.bounce = 0.13 * kick.pow(1.4) * (1.0 - 0.5 * drop)
        p.clearance = 0.08 * drop
        p.swayX = 0.34 * s * (1.0 - 0.6 * drop)
        p.swayZ = -0.14 * cos(TAU * x * 0.5)
        p.spin = 360.0 * smoothstep(4.2, 5.2, x)
        p.pop = 0.06 * groove
        p.flare = 0.35 + 0.4 * drop + 0.2 * groove

        p.limb(HEAD, 0.18 * kick - 0.25 * drop, -0.35 * s, -0.12 * s)
        p.limb(BODY, 0.12 * kick, 0.18 * s, 0.06 * c)
        p.limb(RIGHT_LEG, -0.95 * right - 0.55 * drop, 0.45 * right, 0.12 + 0.22 * right)
        p.limb(LEFT_LEG, -0.95 * left - 0.55 * drop, -0.45 * left, -0.12 - 0.22 * left)
        // Open chest arms wide on the half-beat, cross low in front on the beat.
        val open = 1.0 - kick
        p.limb(RIGHT_ARM, -0.65 - 1.25 * left - 0.6 * drop, -0.45 * right + 0.35 * open, 0.35 + 0.85 * open)
        p.limb(LEFT_ARM, -0.65 - 1.25 * right - 0.6 * drop, 0.45 * left - 0.35 * open, -0.35 - 0.85 * open)
    }

    /**
     * Classic low floor 6-step footwork + coffee-grinder helicopter leg sweeps close to the ground, supporting hands
     * planting alternately while the body orbits tight around the cypher.
     */
    private fun sixStep(x: Double, p: Pose) {
        val phase = TAU * x / 2.0 // full 6-step revolution every 2 beats
        val s = sin(phase)
        val c = cos(phase)
        val groove = beatPulse(x, 6.0)

        p.pitch = 68.0 + 10.0 * c
        p.tilt = 22.0 * s
        p.twist = wrap(-180.0 * x)
        p.pivot = HIP
        p.clearance = 0.14
        p.bounce = 0.06 * abs(sin(PI * x))
        p.swayX = 0.28 * cos(phase)
        p.swayZ = 0.28 * sin(phase)
        p.spin = 180.0
        p.pop = 0.05 * groove
        p.flare = 0.72
        p.sparks = 0.35 * groove

        val rSweep = sin(phase)
        val lSweep = sin(phase + PI * 0.85)
        p.limb(HEAD, -0.55, 0.25 * s, 0.0)
        p.limb(BODY, 0.18, 0.2 * c, 0.0)
        // Alternating floor-plant arms (-2.55 reaches straight toward the floor when pitched forward).
        p.limb(RIGHT_ARM, -2.35 - 0.35 * max(s, 0.0), 0.15 * c, 0.35 + 0.25 * max(-s, 0.0))
        p.limb(LEFT_ARM, -2.35 - 0.35 * max(-s, 0.0), 0.15 * c, -0.35 - 0.25 * max(s, 0.0))
        // Wide sweeping hook/helicopter legs whipping past each other.
        p.limb(RIGHT_LEG, -0.65 + 0.75 * rSweep, 0.3 * c, 0.78 + 0.45 * max(c, 0.0))
        p.limb(LEFT_LEG, -0.65 + 0.75 * lSweep, -0.3 * c, -0.78 - 0.45 * max(-c, 0.0))
    }

    /**
     * High-speed barrel -> nutcracker -> munchmill combo: rolling across the upper back and shoulders while rocking in
     * a 3D cone (`pitch` + `tilt` quadrature), legs whipping wide V-scissors into tight tucked munchmills.
     */
    private fun windmill(x: Double, p: Pose) {
        val roll = PI * x * 1.5 // 1.5 barrel rolls per 2 beats
        val s = sin(roll)
        val c = cos(roll)
        val munch = smoothstep(4.5, 6.0, x) // morph into fast tucked munchmills in second half
        val groove = beatPulse(x, 6.5)

        p.pitch = 96.0 + 18.0 * c
        p.tilt = 24.0 * s
        p.twist = wrap(270.0 * x)
        p.pivot = SHOULDER
        p.clearance = 0.2
        p.bounce = 0.05 * abs(s) + 0.06 * munch * abs(sin(PI * x * 2.0))
        p.swayX = 0.22 * c
        p.swayZ = 0.22 * s
        p.spin = 285.0 + 95.0 * munch
        p.pop = 0.06 * groove
        p.flare = 0.92
        p.sparks = 0.5 + 0.25 * munch

        val spread = (1.18 + 0.22 * sin(PI * x)) * (1.0 - 0.55 * munch) + 0.32 * munch
        val kick = 0.52 * sin(PI * x * 2.0) * (1.0 - 0.4 * munch)
        val tuck = -0.95 * munch
        p.limb(HEAD, 0.45, 0.2 * s, 0.0)
        p.limb(BODY, -0.1, 0.15 * s, 0.1 * c)
        p.limb(RIGHT_LEG, tuck - kick, 0.18 * c, spread)
        p.limb(LEFT_LEG, tuck + kick, -0.18 * c, -spread)
        // Barrel arms: rounded in front of the chest like hugging a barrel.
        p.limb(RIGHT_ARM, -1.45 - 0.25 * munch, -0.55, -0.25)
        p.limb(LEFT_ARM, -1.45 - 0.25 * munch, 0.55, 0.25)
    }

    /**
     * Inverted power airflares: the dancer orbits around a tilted overhead cone, launching into ballistic airborne hops
     * every beat between right-hand and left-hand catches while wide straddle legs whip overhead like helicopter blades.
     */
    private fun airflare(x: Double, p: Pose) {
        val cone = PI * x
        val hop = abs(sin(PI * x)) // 0 at hand-catch on integer beats, 1 at airborne apex
        val flight = hop * (2.0 - hop) // parabolic ballistic arc
        val rightCatch = max(cos(PI * x), 0.0)
        val leftCatch = max(-cos(PI * x), 0.0)
        val groove = beatPulse(x, 7.0)

        p.pitch = 138.0 + 24.0 * cos(cone)
        p.tilt = 36.0 * sin(cone)
        p.twist = wrap(360.0 * x * 0.5)
        p.pivot = WAIST
        p.clearance = 0.26
        p.bounce = 0.58 * flight
        p.swayX = 0.36 * cos(cone)
        p.swayZ = 0.36 * sin(cone)
        p.spin = 400.0
        p.pop = 0.08 * groove
        p.flare = 1.0
        p.sparks = 0.85 * (1.0 - flight).pow(2)

        val whip = 0.42 * sin(PI * x * 2.0)
        p.limb(HEAD, -0.35, 0.2 * sin(cone), 0.0)
        p.limb(BODY, -0.18, 0.0, 0.16 * sin(cone))
        p.limb(RIGHT_LEG, -0.28 + whip, 0.2, 1.28)
        p.limb(LEFT_LEG, -0.28 - whip, -0.2, -1.28)
        // Catching arm reaches overhead to plant (-2.95 rad); free arm flares out dynamically.
        p.limb(RIGHT_ARM, -2.05 - 0.95 * rightCatch, 0.0, 0.22 + 0.95 * leftCatch)
        p.limb(LEFT_ARM, -2.05 - 0.95 * leftCatch, 0.0, -0.22 - 0.95 * rightCatch)
    }

    /**
     * 1990s into one-arm handhops: inverted vertical spin balanced on the right palm, free left arm Reaching to the
     * sky, rhythmic pogo hops on every half-beat while legs scissor from a wide V into a stylish figure-4 pike.
     */
    private fun handhop1990(x: Double, p: Pose) {
        val hop = abs(sin(PI * x * 2.0)) // crisp pogo hand-hops twice per beat
        val figureFour = smoothstep(2.0, 4.0, x)
        val groove = beatPulse(x, 7.0)

        p.pitch = 174.0
        p.tilt = 14.0 + 4.0 * sin(PI * x)
        p.twist = wrap(45.0 * sin(PI * x * 0.5))
        p.pivot = HEIGHT
        p.clearance = 0.34
        p.bounce = 0.22 * hop.pow(1.3)
        p.spin = 500.0 + 140.0 * smoothstep(0.0, 3.0, x)
        p.pop = 0.07 * groove
        p.flare = 0.95
        p.sparks = 0.9 * (1.0 - hop).pow(2)

        p.limb(HEAD, -0.35, 0.0, 0.15)
        p.limb(BODY, -0.08, 0.0, 0.1)
        // Right arm locked straight overhead onto the floor; left arm styling out at hip/rib.
        p.limb(RIGHT_ARM, -3.05, 0.0, 0.12)
        p.limb(LEFT_ARM, -0.45 - 0.35 * sin(PI * x), 0.25, -1.25 - 0.2 * hop)
        // Legs morph from wide straddle into a bent-knee figure-4 lock.
        p.limb(RIGHT_LEG, -0.15 - 1.15 * figureFour, 0.25 * figureFour, 0.85 * (1.0 - 0.55 * figureFour))
        p.limb(LEFT_LEG, 0.15 - 0.2 * figureFour, 0.0, -0.85 * (1.0 - 0.45 * figureFour))
    }

    /**
     * Turbo drill headspin: balanced dead-centre on the crown of the head, accelerating from wide T-stance air-brakes
     * into a razor-tight pencil drill at 900°/beat with a blaze of friction sparks.
     */
    private fun headspin(x: Double, p: Pose) {
        val drill = smoothstep(1.2, 4.2, x) // pulls limbs inward to conserve angular momentum
        val wobble = (1.0 - 0.65 * drill) * 5.0
        val groove = beatPulse(x, 7.0)

        p.pitch = 180.0 + wobble * cos(PI * x)
        p.tilt = wobble * sin(PI * x)
        p.pivot = HEIGHT
        p.clearance = 0.03
        p.spin = 420.0 + 480.0 * drill // accelerates up to 2.5 full rotations per beat!
        p.pop = 0.05 * groove + 0.04 * drill
        p.flare = 0.88 + 0.12 * drill
        p.sparks = 0.65 + 0.35 * drill

        val sway = 0.32 * sin(PI * x) * (1.0 - 0.8 * drill)
        val legSpread = 0.95 * (1.0 - 0.88 * drill) + 0.06
        val armSpread = 1.45 * (1.0 - 0.82 * drill) + 0.15
        p.limb(HEAD, 0.0, 0.0, 0.0)
        p.limb(RIGHT_LEG, sway - 0.35 * (1.0 - drill), 0.15 * drill, legSpread)
        p.limb(LEFT_LEG, -sway + 0.35 * (1.0 - drill), -0.15 * drill, -legSpread)
        p.limb(RIGHT_ARM, -0.45 * drill, -0.35 * drill, armSpread)
        p.limb(LEFT_ARM, -0.45 * drill, 0.35 * drill, -armSpread)
    }

    /**
     * Signature two-pose power freeze: snaps hard into an inverted hollowback scorpion on beat 0 (triggering a floor
     * shockwave), then switches on beat 2 into a crisp one-handed Nike airchair freeze with beat micro-pops.
     */
    private fun hollowbackFreeze(x: Double, p: Pose) {
        val switch = smoothstep(1.82, 2.18, x) // sharp snap on beat 2 into second freeze
        val settle = exp(-fraction(x * 0.5) * 7.0)
        val microBounce = 0.025 * beatPulse(x, 8.0)

        // Pose A: Inverted Hollowback (both hands planted, deep arched spine, scorpion legs).
        // Pose B: One-hand Nike Freeze (tilted high on right arm, left hand pointing skyward, legs scissored).
        p.pitch = 152.0 + (136.0 - 152.0) * switch + 4.0 * settle
        p.tilt = 8.0 + (46.0 - 8.0) * switch
        p.twist = 12.0 + (34.0 - 12.0) * switch
        p.pivot = 1.68 + (1.58 - 1.68) * switch
        p.clearance = 0.18 + 0.08 * switch
        p.bounce = microBounce + 0.04 * settle
        p.spin = 0.0
        p.pop = 0.11 * settle + 0.04 * beatPulse(x, 7.0)
        p.flare = 0.45 + 0.35 * settle
        p.impact = if (x < 0.35 || x in 1.95..2.3) 1.0 else 0.0

        p.limb(HEAD, -0.55 + 0.2 * switch, 0.35 * switch, -0.2 * switch)
        p.limb(BODY, -0.32 + 0.18 * switch, 0.15 * switch, 0.12 * switch)
        p.limb(RIGHT_ARM, -2.92, 0.0, 0.25 + 0.12 * switch)
        p.limb(LEFT_ARM, -2.92 + 2.45 * switch, 0.2 * switch, -0.25 - 1.15 * switch)
        p.limb(RIGHT_LEG, 0.85 - 2.25 * switch, 0.15, 0.32 + 0.25 * switch)
        p.limb(LEFT_LEG, 0.45 - 0.75 * switch, -0.15, -0.48 - 0.35 * switch)
    }

    /**
     * Showstopper grand finale: coiled crouch wind-up, explosive high-altitude layout backflip with a 720° corkscrew
     * twist, and a superhero three-point impact landing with shockwave ring!
     */
    private fun corkscrewFinale(x: Double, p: Pose) {
        val launchStart = 0.55
        val landBeat = 3.05
        when {
            x < launchStart -> {
                // Deep spring-loaded crouch wind-up.
                val coil = smoothstep(0.0, launchStart, x)
                p.pitch = 32.0 * coil
                p.pivot = HIP
                p.clearance = 0.02
                p.pop = -0.06 * coil
                p.flare = 0.5
                p.limb(HEAD, -0.25 * coil, 0.0, 0.0)
                p.limb(BODY, 0.25 * coil, 0.0, 0.0)
                p.limb(RIGHT_LEG, -1.15 * coil, 0.15, 0.18)
                p.limb(LEFT_LEG, -1.15 * coil, -0.15, -0.18)
                p.limb(RIGHT_ARM, 0.75 * coil, 0.0, 0.35)
                p.limb(LEFT_ARM, 0.75 * coil, 0.0, -0.35)
            }
            x < landBeat -> {
                // Full airborne layout backflip + 720° corkscrew!
                val t = (x - launchStart) / (landBeat - launchStart)
                val arc = 4.0 * t * (1.0 - t)
                val flip = smoothstep(0.0, 1.0, t)
                val settleIn = 1.0 - smoothstep(0.0, 0.18, t)
                val settleOut = smoothstep(0.82, 1.0, t)
                p.pitch = 32.0 * settleIn - 360.0 * flip + 48.0 * settleOut
                p.tilt = 22.0 * sin(TAU * t) - 8.0 * settleOut
                p.twist = wrap(720.0 * flip - 24.0 * settleOut)
                p.pivot = WAIST * (1.0 - 0.3 * settleIn - 0.3 * settleOut) + HIP * (0.3 * settleIn + 0.3 * settleOut)
                p.clearance = 0.02 + 0.1 * arc
                p.bounce = 1.65 * arc
                p.spin = 720.0 * arc
                p.pop = 0.1 * arc
                p.flare = 1.0
                val eagle = sin(PI * t)
                p.limb(HEAD, -0.35 * eagle - 0.45 * settleOut, 0.25 * settleOut, 0.0)
                p.limb(BODY, -0.2 * eagle + 0.25 * settleOut, -0.2 * settleOut, 0.0)
                p.limb(RIGHT_LEG, -0.55 * eagle - 1.15 * settleIn - 1.25 * settleOut, 0.18, 0.65 * eagle + 0.2)
                p.limb(LEFT_LEG, -0.55 * eagle - 1.15 * settleIn - 0.55 * settleOut, -0.18, -0.65 * eagle - 0.2)
                p.limb(RIGHT_ARM, -0.85 * eagle + 0.75 * settleIn - 1.85 * settleOut, -0.25 * eagle, 1.35 * eagle + 0.22)
                p.limb(LEFT_ARM, -0.85 * eagle + 0.75 * settleIn + 0.85 * settleOut, 0.25 * eagle + 0.2 * settleOut, -1.35 * eagle - 0.95 * settleOut - 0.35 * settleIn)
            }
            else -> {
                // Superhero three-point landing & rising chest pop!
                val land = (x - landBeat) / (4.0 - landBeat)
                val slam = exp(-land * 6.0)
                val rise = smoothstep(0.35, 1.0, land)
                p.pitch = 48.0 * (1.0 - rise)
                p.tilt = -8.0 * (1.0 - rise)
                p.twist = -24.0 * (1.0 - rise)
                p.pivot = HIP * (1.0 - rise)
                p.clearance = 0.05 * (1.0 - rise)
                p.pop = 0.14 * slam
                p.flare = 0.85 * (1.0 - 0.5 * rise)
                p.sparks = 0.95 * slam
                p.impact = if (land < 0.35) 1.0 else 0.0
                p.limb(HEAD, -0.45 * (1.0 - rise), 0.25 * (1.0 - rise), 0.0)
                p.limb(BODY, 0.25 * (1.0 - rise), -0.2 * (1.0 - rise), 0.0)
                p.limb(RIGHT_LEG, -1.25 * (1.0 - rise), 0.2, 0.22)
                p.limb(LEFT_LEG, -0.55 * (1.0 - rise), -0.25, -0.35 * (1.0 - rise) - 0.08)
                // Right fist planted into the floor, left arm swept heroically back!
                p.limb(RIGHT_ARM, -1.85 * (1.0 - rise) - 0.35 * rise, 0.0, 0.2 + 0.2 * rise)
                p.limb(LEFT_ARM, 0.85 * (1.0 - rise) - 0.35 * rise, 0.2, -0.95 * (1.0 - rise) - 0.2 * rise)
            }
        }
    }

    /** Crisp exponential beat kick at [decay] sharpness. */
    private fun beatPulse(x: Double, decay: Double): Double = exp(-fraction(x) * decay)

    private fun rad(degrees: Double) = degrees * (PI / 180)

    /** Degrees wrapped to -180..180. */
    private fun wrap(degrees: Double): Double = degrees - 360 * floor((degrees + 180) / 360)
}
