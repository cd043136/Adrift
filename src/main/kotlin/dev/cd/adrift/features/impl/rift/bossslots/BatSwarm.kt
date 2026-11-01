package dev.cd.adrift.features.impl.rift.bossslots

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.utils.render.drawText
import dev.cd.adrift.utils.VampireDrop
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * In-world alternative to [BossDropsSpinner]: the dead boss bursts into bats
 * that spiral up, converge into a ball above the corpse and pop to reveal the
 * drops. Nothing captures input, so the player keeps playing throughout.
 *
 * Drop chat lands a few ticks after the death is detected, so the first
 * [BASE_BATS] bats burst out before the result is known. Once a drop arrives
 * (or [WINDOW] passes with none, meaning Coven Seal) the swarm picks its
 * [Level]: extra bats fly in and the swirl lasts longer for rarer drops. The
 * swarm stays [SWARM_RED] for every drop until the pop so the colour never
 * spoils the result.
 *
 * All motion is a function of time since death, so it renders smoothly per
 * frame. Call [render] from the world render event and [renderHud] from a HUD
 * layer; [onReveal] fires once on the client thread when the loot is shown.
 */
class BatSwarm(
    private val origin: Vec3,
    private val playSounds: Boolean,
    private val onReveal: (List<VampireDrop>) -> Unit,
    /** Test hook: drops treated as arriving by chat [SCRIPTED_DELAY] after death. */
    private val scriptedDrops: List<VampireDrop>? = null,
) {
    private class Bat(
        val reinforcement: Boolean,
        val dir: Vec3,
        val dist: Double,
        val a0: Double,
        val spin: Double,
        val height: Double,
        val phase: Double,
        val flapRate: Double,
        val size: Double,
        val joinT: Double,
        val from: Vec3,
        val orbR: Double,
        val orbP: Double,
        val orbW: Double,
        val orbT: Double,
        val flee: Vec3,
        val popV: Double,
        val popDir: Vec3,
    )

    private class State(val pos: Vec3, val alpha: Double)

    private class Item(val drop: VampireDrop, val hover: Vec3)

    private val mc = Minecraft.getInstance()
    private val startNs = System.nanoTime()
    private val rng = Random(startNs)
    private val bats = ArrayList<Bat>()

    private var drops: List<VampireDrop> = emptyList()
    private var level = 0
    private var decisionT = -1.0
    private var upgradeT = 0.0
    private var convT = Double.MAX_VALUE
    private var popT = Double.MAX_VALUE
    private var convLocked = false
    private var cancelT = -1.0
    private var scriptedApplied = false

    @Volatile
    private var revealT = -1.0
    private var items: List<Item> = emptyList()

    private var lastT = 0.0
    private var nextFlapT = T_BURST
    private var screeched = false

    var finished = false
        private set

    /** True until the reveal, so physical drops can't spoil it. */
    val isRevealed: Boolean
        get() = revealT >= 0

    /** Real item entities stay hidden until the revealed sprites have settled onto the ground. */
    val hidesItems: Boolean
        get() = revealT < 0 || elapsed() < revealT + HOVER + SETTLE

    init {
        repeat(BASE_BATS) { bats.add(newBat(reinforcement = false, joinT = T_BURST)) }
    }

    private fun elapsed(): Double = (System.nanoTime() - startNs) / 1e9

    /**
     * Steers the swarm toward the parsed drops. Rarer drops upgrade the swarm
     * (more bats, longer swirl) until it starts converging; after the reveal
     * the result is locked.
     */
    fun updateDrops(parsed: List<VampireDrop>) {
        if (revealT >= 0) return
        val real = VampireDrop.resolveWinningDrops(parsed).filter { it != VampireDrop.COVEN_SEAL }
        if (real.isEmpty()) return
        drops = real
        val t = elapsed()
        if (decisionT < 0) decisionT = t
        val newLevel = real.maxOf { levelOf(it) }
        if (newLevel > level && !convLocked) {
            level = newLevel
            upgradeT = t
            recomputeTimes()
            spawnReinforcements(t)
        }
    }

    /** Next boss spawned: scatter the bats and show the loot as soon as it is known. */
    fun interrupt() {
        if (revealT >= 0 || cancelT >= 0) return
        cancelT = elapsed()
    }

    private fun recomputeTimes() {
        if (decisionT < 0) return
        val lv = LEVELS[level]
        convT = max(max(T_TOR, decisionT) + lv.swirl, upgradeT + 0.35)
        popT = convT + lv.converge
    }

    private fun spawnReinforcements(t: Double) {
        val target = BASE_BATS + LEVELS[level].extra
        val earliest = max(t, T_BURST + 0.15)
        while (bats.size < target) {
            val joinT = min(earliest + rng.nextDouble() * 0.45, convT - 0.55).coerceAtLeast(t)
            bats.add(newBat(reinforcement = true, joinT = joinT))
        }
    }

    private fun newBat(reinforcement: Boolean, joinT: Double): Bat {
        val th = rng.nextDouble() * PI * 2
        val ph = 0.15 + rng.nextDouble()
        val fa = rng.nextDouble() * PI * 2
        val fd = 9 + rng.nextDouble() * 6
        return Bat(
            reinforcement = reinforcement,
            dir = Vec3(cos(th) * cos(ph), sin(ph), sin(th) * cos(ph)),
            dist = 1.3 + rng.nextDouble() * 1.8,
            a0 = rng.nextDouble() * PI * 2,
            spin = 2.4 + rng.nextDouble() * 1.8,
            height = 0.4 + rng.nextDouble() * 3.0,
            phase = rng.nextDouble() * PI * 2,
            flapRate = 11 + rng.nextDouble() * 13,
            size = 0.8 + rng.nextDouble() * 0.45,
            joinT = joinT,
            from = Vec3(cos(fa) * fd, 4 + rng.nextDouble() * 6, sin(fa) * fd),
            orbR = 0.22 + rng.nextDouble() * 0.4,
            orbP = rng.nextDouble() * PI * 2,
            orbW = (6 + rng.nextDouble() * 5) * if (rng.nextBoolean()) -1 else 1,
            orbT = rng.nextDouble() * 1.2,
            flee = Vec3(rng.nextDouble() * 2 - 1, 0.25 + rng.nextDouble() * 0.8, rng.nextDouble() * 2 - 1).normalize(),
            popV = 4 + rng.nextDouble() * 6,
            popDir = Vec3(rng.nextDouble() * 2 - 1, rng.nextDouble() * 1.6 - 0.35, rng.nextDouble() * 2 - 1).normalize(),
        )
    }

    // ---- timeline ----

    private fun update(t: Double) {
        if (scriptedDrops != null && !scriptedApplied && t >= SCRIPTED_DELAY) {
            scriptedApplied = true
            updateDrops(scriptedDrops)
        }
        if (decisionT < 0 && t >= WINDOW) {
            // No drop line by now: Coven Seal.
            decisionT = WINDOW
            recomputeTimes()
        }
        if (!convLocked && t >= convT) convLocked = true
        if (revealT < 0 && decisionT >= 0) {
            if (cancelT >= 0) reveal(t, fromBall = false)
            else if (t >= popT) reveal(popT, fromBall = true)
        }
        fireCues(lastT, t)
        lastT = t
        if (revealT >= 0 && t > revealT + HOVER + END_HOLD) finished = true
    }

    private fun reveal(at: Double, fromBall: Boolean) {
        revealT = at
        val shown = drops.ifEmpty { listOf(VampireDrop.COVEN_SEAL) }
        val right = horizontalRight(mc.gameRenderer.mainCamera.position(), origin)
        items = shown.mapIndexed { j, drop ->
            val offset = (j - (shown.size - 1) / 2.0) * 1.2
            Item(drop, origin.add(right.scale(offset)).add(0.0, HOVER_Y, 0.0))
        }
        if (fromBall) {
            val top = shown.minBy { it.rank }
            val k = origin.add(0.0, BALL_Y, 0.0)
            repeat(25 + level * 20) { particle(DustParticleOptions(top.rarityColor and 0xFFFFFF, 1.4f), k, 1.4, Vec3.ZERO) }
            repeat(12 + level * 6) {
                val d = randomDir()
                particle(ParticleTypes.FIREWORK, k, 0.4, d.scale(0.15 + rng.nextDouble() * 0.2))
            }
            sound(SoundEvents.FIREWORK_ROCKET_BLAST, k, 1.2f, 1.0f)
            if (drops.isNotEmpty()) sound(SoundEvents.EXPERIENCE_ORB_PICKUP, k, 1.0f, 0.8f)
        } else {
            items.forEach { repeat(8) { _ -> particle(ParticleTypes.POOF, it.hover, 0.3, Vec3.ZERO) } }
            sound(SoundEvents.ITEM_PICKUP, origin.add(0.0, HOVER_Y, 0.0), 1.0f, 0.8f)
        }
        onReveal(drops)
    }

    private fun fireCues(prev: Double, t: Double) {
        if (prev < T_BURST && t >= T_BURST) {
            val body = origin.add(0.0, 0.9, 0.0)
            repeat(36) { particle(DustParticleOptions(if (rng.nextBoolean()) 0x7D0A12 else 0xC8101C, 1.5f), body, 0.6, Vec3.ZERO) }
            repeat(14) { particle(ParticleTypes.POOF, body, 0.5, randomDir().scale(0.05)) }
            sound(SoundEvents.BAT_TAKEOFF, body, 1.0f, 0.9f)
            sound(SoundEvents.BAT_TAKEOFF, body, 0.8f, 1.2f)
        }
        val swarming = t >= T_BURST && revealT < 0 && cancelT < 0
        if (swarming && t >= nextFlapT) {
            nextFlapT = t + 0.25
            sound(SoundEvents.BAT_AMBIENT, origin.add(0.0, 2.0, 0.0), 0.25f + level * 0.08f, 0.8f + rng.nextFloat() * 0.6f)
        }
        if (swarming && level == 4 && !screeched && t >= T_TOR) {
            screeched = true
            sound(SoundEvents.BAT_DEATH, origin.add(0.0, 2.0, 0.0), 1.5f, 0.5f)
            sound(SoundEvents.PHANTOM_SWOOP, origin.add(0.0, 2.0, 0.0), 1.0f, 1.3f)
        }
        if (cancelT < 0 && prev < convT && t >= convT) {
            sound(SoundEvents.PHANTOM_FLAP, origin.add(0.0, BALL_Y, 0.0), 1.0f, 0.8f)
            sound(SoundEvents.PHANTOM_SWOOP, origin.add(0.0, BALL_Y, 0.0), 0.8f, 1.2f)
        }
    }

    // ---- motion (positions relative to origin) ----

    private fun tornadoPos(b: Bat, t: Double): Vec3 {
        val jackpot = level == 4
        val sp = 1 + 0.6 * smooth((t - T_TOR) / 1.2) + if (jackpot) 0.7 * smooth((t - T_TOR) / 2) else 0.0
        val a = b.a0 + b.spin * (t - T_BURST) * sp
        val h = b.height * if (jackpot) 1.35 else 1.0
        val y = h * (0.75 + 0.25 * smooth((t - T_TOR) / 0.8)) + 0.2 * sin(t * 2.3 + b.phase)
        val r = (0.45 + y * 0.4) * (1 + 0.12 * sin(t * 3.1 + b.phase)) * if (jackpot) 1.25 else 1.0
        return Vec3(cos(a) * r, y, sin(a) * r)
    }

    private fun ballPos(b: Bat, t: Double): Vec3 {
        val a = b.orbP + b.orbW * t
        val r = b.orbR * if (level == 4) 1.6 else 1.0
        return Vec3(cos(a) * r, BALL_Y + sin(a * 0.7 + b.orbT) * r * 0.8, sin(a) * r)
    }

    private fun batRaw(b: Bat, t: Double): State? {
        if (t < b.joinT) return null
        val tornado = tornadoPos(b, t)
        var p = if (!b.reinforcement) {
            val burst = Vec3(0.0, 0.6, 0.0).add(b.dir.scale(b.dist * easeOut((t - T_BURST) / 0.45)))
            burst.lerp(tornado, smooth((t - T_BURST - 0.25) / 0.5))
        } else {
            b.from.lerp(tornado, smooth((t - b.joinT) / 0.6))
        }
        if (t > convT) p = p.lerp(ballPos(b, t), smooth((t - convT) / (popT - convT)))
        if (t > popT) {
            val dt = t - popT
            if (dt > 1.2) return null
            val d = b.popV * (1 - exp(-2.2 * dt)) / 2.2 * 1.6
            return State(ballPos(b, popT).add(b.popDir.scale(d)).add(0.0, dt * 1.2, 0.0), 1 - smooth(dt / 1.1))
        }
        return State(p, if (b.reinforcement) smooth((t - b.joinT) / 0.25) else 1.0)
    }

    private fun batPos(b: Bat, t: Double): State? {
        val c = cancelT
        if (c < 0 || t <= c || c >= popT + 1.2) return batRaw(b, t)
        val base = batRaw(b, c) ?: return null
        val dt = t - c
        if (dt > 0.45) return null
        return State(base.pos.add(b.flee.scale(dt * 9)), base.alpha * (1 - dt / 0.45))
    }

    // ---- rendering ----

    fun render(event: RenderEvent.Extract) {
        val t = elapsed()
        update(t)
        if (finished) return

        val camera = mc.gameRenderer.mainCamera.position()
        val pose = event.context.poseStack().last()
        val buffers = event.context.bufferSource()

        // Same red for every drop until the pop; the scattering bats flash the real rarity.
        val top = drops.minByOrNull { it.rank } ?: VampireDrop.COVEN_SEAL
        val popped = t >= popT && cancelT < 0
        val base = if (popped) top.rarityColor else SWARM_RED
        val pulse = if (popped) exp(-(t - popT) * 4) else 0.0
        val wing = mix(0x1A0C12, base, 0.45 + 0.4 * pulse)
        val body = mix(0x120810, base, 0.25 + 0.4 * pulse)

        val quads = buffers.getBuffer(RenderTypes.debugFilledBox())
        for (b in bats) drawBat(quads, pose, camera, b, t, wing, body)

        val itemStates = items.map { it to itemState(it, t) }
        for ((item, st) in itemStates) {
            if (st == null || st.beamAlpha <= 0) continue
            drawBeam(quads, pose, camera, item, st.pos, st.beamAlpha)
        }

        for ((item, st) in itemStates) {
            if (st == null || st.alpha <= 0) continue
            drawSprite(buffers.getBuffer(RenderTypes.entityTranslucent(item.drop.textureId)), pose, camera, item.drop, st)
        }

        itemStates.forEachIndexed { j, (item, st) ->
            if (st == null || t > revealT + HOVER + 0.3) return@forEachIndexed
            val stagger = if (items.size > 1) (j % 2) * 0.32 else 0.0
            val labelPos = st.pos.add(0.0, 0.62 + stagger, 0.0)
            val scale = max(1.4f, (camera.distanceTo(labelPos) * 0.08).toFloat())
            event.drawText("${item.drop.color}${item.drop.displayName}", labelPos, scale, false)
        }
    }

    /** Darkens the sky for a jackpot over the last 0.7s of the swirl, clearing after the pop. */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (finished || level != 4 || cancelT >= 0) return
        val t = elapsed()
        val dark = smooth((t - (convT - 0.7)) / 0.7) * (1 - smooth((t - popT - 0.3) / 1.2))
        if (dark <= 0.01) return
        val w = graphics.guiWidth()
        val h = graphics.guiHeight()
        graphics.fill(0, 0, w, h, argb(0.45 * dark, 0x0A0004))
        val edge = argb(0.35 * dark, SWARM_RED)
        graphics.fillGradient(0, 0, w, h / 3, edge, 0)
        graphics.fillGradient(0, h - h / 3, w, h, 0, edge)
    }

    private fun drawBat(buf: VertexConsumer, pose: PoseStack.Pose, camera: Vec3, b: Bat, t: Double, wing: Int, body: Int) {
        val st = batPos(b, t) ?: return
        val prev = batPos(b, t - 0.03)
        val p = origin.add(st.pos)
        val vel = if (prev != null) st.pos.subtract(prev.pos) else Vec3.ZERO
        val f = if (vel.lengthSqr() > 1e-8) vel.normalize() else Vec3(-sin(b.a0), 0.0, cos(b.a0))
        var r = f.cross(UP)
        r = if (r.lengthSqr() < 1e-4) Vec3(1.0, 0.0, 0.0) else r.normalize()
        val u = r.cross(f)
        val s = b.size * 1.15
        fun at(lx: Double, ly: Double, lz: Double): Vec3 =
            p.add(r.scale(lx * s)).add(u.scale(ly * s)).add(f.scale(lz * s))

        val a = st.alpha
        val wingC = argb(a, wing)
        val bodyC = argb(a, body)
        val flap = sin(t * b.flapRate * PI + b.phase)
        val inner = flap * 0.9
        val outer = inner * 1.4
        for (side in intArrayOf(1, -1)) {
            val ci = cos(inner) * side
            val si = sin(inner)
            val co = cos(outer) * side
            val so = sin(outer)
            val shoulderF = at(0.03 * ci, 0.03 * si, 0.05)
            val shoulderB = at(0.03 * ci, 0.03 * si, -0.07)
            val elbowF = at(0.13 * ci, 0.13 * si, 0.04)
            val elbowB = at(0.13 * ci, 0.13 * si, -0.06)
            val tipF = at(0.13 * ci + 0.13 * co, 0.13 * si + 0.13 * so, 0.0)
            val tipB = at(0.13 * ci + 0.11 * co, 0.13 * si + 0.11 * so, -0.035)
            quad(buf, pose, camera, shoulderF, elbowF, elbowB, shoulderB, wingC)
            quad(buf, pose, camera, elbowF, tipF, tipB, elbowB, wingC)
        }
        quad(buf, pose, camera, at(-0.04, 0.0, -0.1), at(0.04, 0.0, -0.1), at(0.04, 0.0, 0.09), at(-0.04, 0.0, 0.09), bodyC)
        quad(buf, pose, camera, at(0.0, -0.05, -0.1), at(0.0, 0.05, -0.1), at(0.0, 0.05, 0.09), at(0.0, -0.05, 0.09), bodyC)
        quad(buf, pose, camera, at(-0.035, 0.0, 0.1), at(0.035, 0.0, 0.1), at(0.035, 0.065, 0.1), at(-0.035, 0.065, 0.1), bodyC)
    }

    private class ItemState(val pos: Vec3, val size: Double, val spin: Double, val alpha: Double, val beamAlpha: Double)

    private fun itemState(item: Item, t: Double): ItemState? {
        val tr = revealT
        if (tr < 0 || t < tr) return null
        val dt = t - tr
        val settleT = tr + HOVER
        val fromBall = cancelT < 0
        val ball = origin.add(0.0, BALL_Y, 0.0)
        var pos = if (fromBall) ball.lerp(item.hover, easeOutBack(dt / 0.6)) else item.hover
        val grow = if (fromBall) 1.0 else easeOutBack(dt / 0.3)
        var size = 0.6
        pos = pos.add(0.0, 0.08 * sin(t * 2.6 + item.hover.x), 0.0)
        if (t > settleT) {
            val u = ((t - settleT) / SETTLE).coerceIn(0.0, 1.0)
            val y = HOVER_Y + (0.3 - HOVER_Y) * u * u + if (u >= 1) 0.06 * sin(t * 3) else 0.0
            pos = Vec3(pos.x, origin.y + y, pos.z)
            size = 0.6 + (0.35 - 0.6) * u
        }
        val alpha = 1 - smooth((t - settleT - SETTLE) / 0.4)
        val beam = 1 - smooth((t - settleT) / 1.4)
        return ItemState(pos, size * grow, dt * 2.4 + item.hover.x, alpha, beam)
    }

    private fun drawSprite(buf: VertexConsumer, pose: PoseStack.Pose, camera: Vec3, drop: VampireDrop, st: ItemState) {
        val (texW, texH) = spriteSize(drop)
        val h = st.size
        val w = st.size * texW / texH * max(0.08, abs(cos(st.spin)))
        val right = horizontalRight(camera, st.pos)
        val normal = Vec3(-right.z, 0.0, right.x)
        val bl = st.pos.add(right.scale(-w / 2)).add(0.0, -h / 2, 0.0)
        val br = st.pos.add(right.scale(w / 2)).add(0.0, -h / 2, 0.0)
        val tr = st.pos.add(right.scale(w / 2)).add(0.0, h / 2, 0.0)
        val tl = st.pos.add(right.scale(-w / 2)).add(0.0, h / 2, 0.0)
        val color = argb(st.alpha, 0xFFFFFF)
        // Counter-clockwise as seen from the camera, so culling never hides it.
        texVertex(buf, pose, camera, bl, 0f, 1f, color, normal)
        texVertex(buf, pose, camera, br, 1f, 1f, color, normal)
        texVertex(buf, pose, camera, tr, 1f, 0f, color, normal)
        texVertex(buf, pose, camera, tl, 0f, 0f, color, normal)
    }

    private fun drawBeam(buf: VertexConsumer, pose: PoseStack.Pose, camera: Vec3, item: Item, pos: Vec3, alpha: Double) {
        val right = horizontalRight(camera, pos)
        val bottom = Vec3(pos.x, origin.y + 0.05, pos.z)
        val top = bottom.add(0.0, BEAM_HEIGHT, 0.0)
        val color = item.drop.rarityColor
        for ((width, strength) in listOf(0.34 to 0.35, 0.12 to 0.7)) {
            val half = right.scale(width / 2)
            quad(
                buf, pose, camera,
                bottom.subtract(half), bottom.add(half), top.add(half), top.subtract(half),
                argb(alpha * strength, color), argb(alpha * strength, color), argb(0.0, color), argb(0.0, color),
            )
        }
    }

    // ---- helpers ----

    private fun quad(buf: VertexConsumer, pose: PoseStack.Pose, camera: Vec3, a: Vec3, b: Vec3, c: Vec3, d: Vec3, color: Int) =
        quad(buf, pose, camera, a, b, c, d, color, color, color, color)

    /** Emits both windings so the quad shows from either side whatever the pipeline's culling. */
    private fun quad(
        buf: VertexConsumer, pose: PoseStack.Pose, camera: Vec3,
        a: Vec3, b: Vec3, c: Vec3, d: Vec3,
        ca: Int, cb: Int, cc: Int, cd: Int,
    ) {
        vertex(buf, pose, camera, a, ca)
        vertex(buf, pose, camera, b, cb)
        vertex(buf, pose, camera, c, cc)
        vertex(buf, pose, camera, d, cd)
        vertex(buf, pose, camera, d, cd)
        vertex(buf, pose, camera, c, cc)
        vertex(buf, pose, camera, b, cb)
        vertex(buf, pose, camera, a, ca)
    }

    private fun vertex(buf: VertexConsumer, pose: PoseStack.Pose, camera: Vec3, p: Vec3, color: Int) {
        buf.addVertex(pose, (p.x - camera.x).toFloat(), (p.y - camera.y).toFloat(), (p.z - camera.z).toFloat()).setColor(color)
    }

    private fun texVertex(buf: VertexConsumer, pose: PoseStack.Pose, camera: Vec3, p: Vec3, u: Float, v: Float, color: Int, n: Vec3) {
        buf.addVertex(pose, (p.x - camera.x).toFloat(), (p.y - camera.y).toFloat(), (p.z - camera.z).toFloat())
            .setColor(color)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(FULL_BRIGHT)
            .setNormal(pose, n.x.toFloat(), n.y.toFloat(), n.z.toFloat())
    }

    private fun particle(type: net.minecraft.core.particles.ParticleOptions, center: Vec3, spread: Double, velocity: Vec3) {
        val world = mc.level ?: return
        val d = randomDir().scale(rng.nextDouble() * spread)
        world.addParticle(type, center.x + d.x, center.y + d.y, center.z + d.z, velocity.x, velocity.y, velocity.z)
    }

    private fun sound(sound: SoundEvent, at: Vec3, volume: Float, pitch: Float) {
        if (!playSounds) return
        mc.level?.playLocalSound(at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch, false)
    }

    private fun randomDir(): Vec3 =
        Vec3(rng.nextDouble() * 2 - 1, rng.nextDouble() * 2 - 1, rng.nextDouble() * 2 - 1).let {
            if (it.lengthSqr() < 1e-6) UP else it.normalize()
        }

    /** Level ground direction that is "right" for a camera at [camera] looking toward [target]. */
    private fun horizontalRight(camera: Vec3, target: Vec3): Vec3 {
        val dx = camera.x - target.x
        val dz = camera.z - target.z
        val len = sqrt(dx * dx + dz * dz)
        if (len < 1e-6) return Vec3(1.0, 0.0, 0.0)
        return Vec3(dz / len, 0.0, -dx / len)
    }

    /** One tier of the swarm: bats that fly in once the drop is known, swirl length and converge length (seconds). */
    private data class Level(val extra: Int, val swirl: Double, val converge: Double)

    companion object {
        const val BASE_BATS = 40
        private val LEVELS = listOf(
            Level(extra = 0, swirl = 0.15, converge = 0.30), // Coven Seal
            Level(extra = 28, swirl = 0.55, converge = 0.35), // Quantum, Soultwist
            Level(extra = 60, swirl = 0.95, converge = 0.40), // Guardian, Bubba, Fang-tastic
            Level(extra = 120, swirl = 1.50, converge = 0.45), // Burger, Unfanged, The One
            Level(extra = 140, swirl = 2.60, converge = 0.55), // Sangria Dye
        )

        /** Seconds after death: bats burst, start swirling, and the drop-chat window closes. */
        const val T_BURST = 0.45
        const val T_TOR = 0.9
        const val WINDOW = 0.9
        private const val SCRIPTED_DELAY = 0.25

        private const val HOVER = 2.6
        private const val SETTLE = 0.45
        private const val END_HOLD = 1.6
        private const val HOVER_Y = 1.7
        private const val BALL_Y = 4.0
        private const val BEAM_HEIGHT = 25.0

        private const val SWARM_RED = 0xD40808
        private const val FULL_BRIGHT = 0xF000F0
        private val UP = Vec3(0.0, 1.0, 0.0)

        private val spriteDims = mutableMapOf<VampireDrop, Pair<Int, Int>>()

        fun levelOf(drop: VampireDrop): Int = when (drop) {
            VampireDrop.COVEN_SEAL -> 0
            VampireDrop.QUANTUM, VampireDrop.SOULTWIST -> 1
            VampireDrop.GUARDIAN, VampireDrop.BUBBA, VampireDrop.FANGTASTIC -> 2
            VampireDrop.BURGER, VampireDrop.UNFANGED, VampireDrop.THE_ONE -> 3
            VampireDrop.SANGRIA -> 4
        }

        private fun spriteSize(drop: VampireDrop): Pair<Int, Int> = spriteDims.getOrPut(drop) {
            try {
                Minecraft.getInstance().resourceManager.getResource(drop.textureId).orElseThrow().open().use {
                    NativeImage.read(it).use { image -> image.width to image.height }
                }
            } catch (_: Exception) {
                16 to 16
            }
        }

        private fun smooth(x: Double): Double {
            val u = x.coerceIn(0.0, 1.0)
            return u * u * (3 - 2 * u)
        }

        private fun easeOut(x: Double): Double = 1 - (1 - x.coerceIn(0.0, 1.0)).pow(3)

        private fun easeOutBack(x: Double): Double {
            val u = x.coerceIn(0.0, 1.0) - 1
            val c = 1.7
            return 1 + (c + 1) * u * u * u + c * u * u
        }

        private fun argb(alpha: Double, rgb: Int): Int =
            ((alpha.coerceIn(0.0, 1.0) * 255).toInt() shl 24) or (rgb and 0xFFFFFF)

        private fun mix(from: Int, to: Int, amount: Double): Int {
            val u = amount.coerceIn(0.0, 1.0)
            fun ch(shift: Int): Int {
                val a = (from shr shift) and 0xFF
                val b = (to shr shift) and 0xFF
                return (a + (b - a) * u).toInt() shl shift
            }
            return ch(16) or ch(8) or ch(0)
        }
    }
}
