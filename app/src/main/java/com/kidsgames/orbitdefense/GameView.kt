package com.kidsgames.orbitdefense

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/**
 * Orbit Defense — a 360° tower-defense for kids 7-13.
 *
 * Defend a planet at the centre of the screen from slime-zombies closing in from
 * every direction. **Spin the planet** (drag) to re-aim all turrets at once and
 * **tap** empty space to build the selected turret on the surface. Pick a turret
 * type, save your **shockwave** for a swarm, and keep the planet alive.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private enum class State { READY, PLAYING, GAME_OVER }
    private enum class Ui { NONE, SHOCK, RAPID, CANNON }

    @Volatile private var running = false
    private var thread: Thread? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private var width = 0f
    private var height = 0f
    private var bgShader: Shader? = null
    private var vignetteShader: Shader? = null
    private val nebulas = ArrayList<FloatArray>() // [x, y, radius, colorIndex]

    private lateinit var planet: Planet
    private val defenders = ArrayList<Defender>()
    private val enemies = ArrayList<Enemy>()
    private val shots = ArrayList<Projectile>()
    private val particles = ArrayList<Particle>()
    private val stars = ArrayList<FloatArray>()

    private var state = State.READY
    private var energy = 60f
    private var kills = 0
    private var wave = 1
    private var bestKills = 0
    private var elapsed = 0f
    private var spawnTimer = 1.5f
    private var lastBossWave = 0

    private var selected = TurretType.RAPID
    private var shockCharge = 0f // 0..1
    private var shakeTime = 0f
    private var flashTime = 0f

    // Tuning.
    private val fireIntervalRapid = 0.33f
    private val fireIntervalCannon = 1.1f
    private val maxDefenders = 20

    // Touch / spin state.
    private var prevAngle = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragged = false
    private var activeUi = Ui.NONE

    private val prefs = context.getSharedPreferences("orbitdefense", Context.MODE_PRIVATE)

    // Serializes game-thread update/render against UI-thread touch input, since
    // both touch the same entity lists.
    private val gameLock = Any()

    private val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
    private fun fa(n: Int): String = buildString {
        for (c in n.toString()) append(if (c in '0'..'9') persianDigits[c - '0'] else c)
    }

    init {
        holder.addCallback(this)
        isFocusable = true
        bestKills = prefs.getInt("best", 0)
    }

    // ---- Per-turret stats ----
    private fun cost(t: TurretType) = if (t == TurretType.CANNON) 35f else 15f
    private fun fireInterval(t: TurretType) = if (t == TurretType.CANNON) fireIntervalCannon else fireIntervalRapid
    private fun shotDamage(t: TurretType) = if (t == TurretType.CANNON) 26f else 8f
    private fun range(t: TurretType) = if (t == TurretType.CANNON) width * 0.42f else width * 0.34f
    private fun splash(t: TurretType) = if (t == TurretType.CANNON) width * 0.09f else 0f
    private fun projColor(t: TurretType) = if (t == TurretType.CANNON) Color.parseColor("#FFB74D") else Color.parseColor("#4DD0E1")
    private fun projRadius(t: TurretType) = if (t == TurretType.CANNON) 13f else 8f

    // ---- UI button geometry ----
    private val shockCx get() = width * 0.13f
    private val shockCy get() = height * 0.895f
    private val shockR get() = width * 0.085f
    private val rapidCx get() = width * 0.74f
    private val cannonCx get() = width * 0.90f
    private val turretBtnCy get() = height * 0.895f
    private val turretBtnR get() = width * 0.072f

    // ---- Surface lifecycle ----

    override fun surfaceCreated(holder: SurfaceHolder) = resume()

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        width = w.toFloat()
        height = h.toFloat()
        bgShader = LinearGradient(
            0f, 0f, 0f, height,
            Color.parseColor("#070A1C"), Color.parseColor("#1A1340"), Shader.TileMode.CLAMP
        )
        vignetteShader = RadialGradient(
            width / 2f, height / 2f, height * 0.75f,
            intArrayOf(Color.TRANSPARENT, Color.argb(115, 0, 0, 0)),
            floatArrayOf(0.45f, 1f), Shader.TileMode.CLAMP
        )
        planet = Planet(width / 2f, height / 2f, width * 0.15f)
        buildStarfield()
        buildNebulas()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = pauseGame()

    fun resume() {
        if (running) return
        running = true
        thread = Thread(this).also { it.start() }
    }

    fun pauseGame() {
        running = false
        try {
            thread?.join()
        } catch (_: InterruptedException) {
        }
        thread = null
    }

    // ---- Game lifecycle ----

    private fun startGame() {
        defenders.clear()
        enemies.clear()
        shots.clear()
        particles.clear()
        energy = 60f
        kills = 0
        wave = 1
        elapsed = 0f
        spawnTimer = 1.5f
        lastBossWave = 0
        shockCharge = 0f
        selected = TurretType.RAPID
        planet.hp = planet.maxHp
        planet.rotation = 0f
        state = State.PLAYING
    }

    private fun gameOver() {
        state = State.GAME_OVER
        if (kills > bestKills) {
            bestKills = kills
            prefs.edit().putInt("best", bestKills).apply()
        }
    }

    // ---- Main loop ----

    private var lastFrameNs = System.nanoTime()

    override fun run() {
        lastFrameNs = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            var dt = (now - lastFrameNs) / 1_000_000_000f
            lastFrameNs = now
            if (dt > 0.05f) dt = 0.05f

            synchronized(gameLock) {
                if (state == State.PLAYING) update(dt)
                updateFx(dt)
                val canvas = holder.lockCanvas()
                if (canvas != null) {
                    try {
                        render(canvas)
                    } finally {
                        holder.unlockCanvasAndPost(canvas)
                    }
                }
            }

            val frameMs = (System.nanoTime() - now) / 1_000_000
            val sleep = 16 - frameMs
            if (sleep > 0) try {
                Thread.sleep(sleep)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun update(dt: Float) {
        elapsed += dt
        wave = 1 + (elapsed / 22f).toInt()
        energy = min(140f, energy + 9f * dt)

        // Boss once per boss-wave.
        if (wave % 5 == 0 && wave != lastBossWave) {
            spawnEnemy(EnemyType.BOSS)
            lastBossWave = wave
        }

        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            spawnEnemy(pickEnemyType())
            spawnTimer = (1.7f - wave * 0.11f).coerceAtLeast(0.45f)
        }

        for (d in defenders) {
            d.position(planet)
            d.cooldown -= dt
        }

        // Enemies move in; damage planet on contact.
        val ei = enemies.iterator()
        while (ei.hasNext()) {
            val e = ei.next()
            e.update(dt, planet.cx, planet.cy)
            if (e.dist <= planet.radius + e.radius) {
                damagePlanet(e.contactDamage)
                burst(e.x, e.y, Color.parseColor("#FF5252"), 14, 5f)
                ei.remove()
            } else if (!e.alive) {
                ei.remove()
            }
        }

        // Turrets acquire the nearest enemy in range and fire.
        for (d in defenders) {
            if (d.cooldown > 0f) continue
            val target = nearestEnemy(d.x, d.y, range(d.type)) ?: continue
            d.aim = atan2(target.y - d.y, target.x - d.x)
            val sp = width * 1.0f
            shots.add(
                Projectile(
                    d.x, d.y, cosf(d.aim) * sp, sinf(d.aim) * sp,
                    shotDamage(d.type), splash(d.type), projColor(d.type), projRadius(d.type)
                )
            )
            d.cooldown = fireInterval(d.type)
        }

        // Move shots; resolve hits (with splash for cannons).
        val si = shots.iterator()
        while (si.hasNext()) {
            val s = si.next()
            s.update(dt)
            if (s.x < -50 || s.x > width + 50 || s.y < -50 || s.y > height + 50) {
                si.remove(); continue
            }
            val hit = enemies.firstOrNull { it.alive && hypot(it.x - s.x, it.y - s.y) < it.radius + s.radius }
            if (hit != null) {
                damageEnemy(hit, s.damage)
                if (s.splash > 0f) {
                    for (e in enemies) {
                        if (e !== hit && e.alive && hypot(e.x - s.x, e.y - s.y) < s.splash) {
                            damageEnemy(e, s.damage * 0.5f)
                        }
                    }
                    burst(s.x, s.y, s.color, 16, 6f)
                }
                si.remove()
            }
        }
        enemies.removeAll { !it.alive }
    }

    private fun updateFx(dt: Float) {
        if (shakeTime > 0f) shakeTime -= dt
        if (flashTime > 0f) flashTime -= dt
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.update(dt)
            if (p.life <= 0f) it.remove()
        }
    }

    private fun damageEnemy(e: Enemy, dmg: Float) {
        e.hp -= dmg
        burst(e.x, e.y, Color.parseColor("#4DD0E1"), 5, 4f)
        if (e.hp <= 0f && e.alive) {
            e.alive = false
            kills++
            energy = min(140f, energy + e.reward)
            shockCharge = min(1f, shockCharge + 0.05f)
            burst(e.x, e.y, Color.parseColor("#7CB342"), 14, 5f)
        }
    }

    private fun damagePlanet(amount: Float) {
        planet.hp -= amount
        shakeTime = 0.28f
        flashTime = 0.22f
        if (planet.hp <= 0f) {
            planet.hp = 0f
            gameOver()
        }
    }

    private fun triggerShockwave() {
        if (shockCharge < 1f) return
        shockCharge = 0f
        shakeTime = 0.4f
        for (e in ArrayList(enemies)) damageEnemy(e, 80f)
        // Expanding ring of sparks.
        repeat(40) {
            val a = Random.nextFloat() * (2f * Math.PI.toFloat())
            val sp = Random.nextFloat() * 500f + 200f
            particles.add(Particle(planet.cx, planet.cy, cosf(a) * sp, sinf(a) * sp, Color.parseColor("#4DD0E1"), 7f))
        }
    }

    private fun nearestEnemy(x: Float, y: Float, range: Float): Enemy? {
        var best: Enemy? = null
        var bestD = range
        for (e in enemies) {
            if (!e.alive) continue
            val d = hypot(e.x - x, e.y - y)
            if (d < bestD) {
                bestD = d; best = e
            }
        }
        return best
    }

    private fun pickEnemyType(): EnemyType {
        val r = Random.nextFloat()
        return when {
            wave >= 2 && r < 0.20f -> EnemyType.FAST
            wave >= 3 && r < 0.35f -> EnemyType.TANK
            else -> EnemyType.NORMAL
        }
    }

    private fun spawnEnemy(type: EnemyType) {
        val angle = Random.nextFloat() * (2f * Math.PI.toFloat())
        val dist = hypot(width, height) * 0.55f
        val base = height * (0.045f + wave * 0.004f)
        val (speed, hp, radius, contact, reward) = when (type) {
            EnemyType.NORMAL -> Quint(base, 16f + wave * 5f, width * 0.05f, 8f, 6f)
            EnemyType.FAST -> Quint(base * 1.7f, 10f + wave * 3f, width * 0.036f, 6f, 5f)
            EnemyType.TANK -> Quint(base * 0.6f, 40f + wave * 10f, width * 0.066f, 14f, 10f)
            EnemyType.BOSS -> Quint(base * 0.5f, 200f + wave * 40f, width * 0.11f, 34f, 40f)
        }
        enemies.add(Enemy(type, angle, dist, speed, hp, radius, contact, reward))
    }

    private data class Quint(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float)

    private fun burst(x: Float, y: Float, color: Int, count: Int, size: Float) {
        repeat(count) {
            val a = Random.nextFloat() * (2f * Math.PI.toFloat())
            val sp = Random.nextFloat() * 260f + 60f
            particles.add(Particle(x, y, cosf(a) * sp, sinf(a) * sp, color, size))
        }
    }

    // ---- Rendering ----

    private fun render(canvas: Canvas) {
        drawBackground(canvas)

        canvas.save()
        if (shakeTime > 0f) {
            val i = (shakeTime / 0.4f).coerceIn(0f, 1f)
            canvas.translate((Random.nextFloat() - 0.5f) * width * 0.05f * i, (Random.nextFloat() - 0.5f) * width * 0.05f * i)
        }
        if (::planet.isInitialized) {
            planet.draw(canvas, paint)
            for (d in defenders) d.draw(canvas, paint)
        }
        for (e in enemies) e.draw(canvas, paint)
        for (s in shots) s.draw(canvas, paint)
        for (p in particles) p.draw(canvas, paint)
        canvas.restore()

        // Damage flash.
        if (flashTime > 0f) {
            paint.color = Color.argb((flashTime / 0.22f * 90f).toInt().coerceIn(0, 90), 255, 40, 40)
            canvas.drawRect(0f, 0f, width, height, paint)
        }

        paint.shader = vignetteShader
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = null

        when (state) {
            State.READY -> drawOverlay(
                canvas,
                context.getString(R.string.game_title),
                context.getString(R.string.how_to_spin),
                context.getString(R.string.tap_to_start)
            )
            State.PLAYING -> drawHud(canvas)
            State.GAME_OVER -> drawOverlay(
                canvas,
                context.getString(R.string.game_over),
                context.getString(R.string.over_summary, fa(kills), fa(bestKills)),
                context.getString(R.string.tap_to_restart)
            )
        }
    }

    private fun drawBackground(canvas: Canvas) {
        paint.shader = bgShader
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = null
        for (n in nebulas) {
            paint.shader = RadialGradient(
                n[0], n[1], n[2],
                intArrayOf(nebulaColor(n[3].toInt()), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(n[0], n[1], n[2], paint)
        }
        paint.shader = null
        for (s in stars) {
            paint.color = Color.argb((90 + s[3] * 165).toInt().coerceIn(0, 255), 255, 255, 255)
            canvas.drawCircle(s[0], s[1], s[2], paint)
        }
    }

    private fun nebulaColor(i: Int) = when (i) {
        0 -> Color.argb(80, 86, 40, 140)
        1 -> Color.argb(72, 40, 90, 160)
        else -> Color.argb(46, 30, 120, 120)
    }

    private fun drawHud(canvas: Canvas) {
        textPaint.textSize = width * 0.055f
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(context.getString(R.string.hud_kills, fa(kills)), width * 0.95f, height * 0.07f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(context.getString(R.string.hud_wave, fa(wave)), width * 0.05f, height * 0.07f, textPaint)

        // Energy bar just above the buttons.
        val barW = width * 0.9f
        val barX = width * 0.05f
        val barY = height * 0.965f
        val barH = height * 0.02f
        paint.color = Color.argb(70, 255, 255, 255)
        canvas.drawRoundRect(barX, barY, barX + barW, barY + barH, barH, barH, paint)
        paint.color = Color.parseColor("#FFD23F")
        canvas.drawRoundRect(barX, barY, barX + barW * (energy / 140f), barY + barH, barH, barH, paint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = width * 0.035f
        canvas.drawText(context.getString(R.string.hud_energy), width / 2f, barY - height * 0.006f, textPaint)

        drawShockButton(canvas)
        drawTurretButton(canvas, rapidCx, TurretType.RAPID)
        drawTurretButton(canvas, cannonCx, TurretType.CANNON)
    }

    private fun drawShockButton(canvas: Canvas) {
        val ready = shockCharge >= 1f
        if (ready) paint.setShadowLayer(20f, 0f, 0f, Color.parseColor("#4DD0E1"))
        paint.color = if (ready) Color.parseColor("#4DD0E1") else Color.argb(120, 40, 60, 80)
        canvas.drawCircle(shockCx, shockCy, shockR, paint)
        paint.clearShadowLayer()
        // Charge ring.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        paint.color = Color.parseColor("#B2EBF2")
        canvas.drawArc(shockCx - shockR, shockCy - shockR, shockCx + shockR, shockCy + shockR, -90f, 360f * shockCharge, false, paint)
        paint.style = Paint.Style.FILL
        // Simple lightning-ish spark icon.
        paint.color = Color.WHITE
        val s = shockR * 0.5f
        canvas.drawCircle(shockCx, shockCy, s * 0.5f, paint)
        for (k in 0 until 6) {
            val a = k * (Math.PI.toFloat() / 3f)
            canvas.drawCircle(shockCx + cosf(a) * s, shockCy + sinf(a) * s, s * 0.18f, paint)
        }
    }

    private fun drawTurretButton(canvas: Canvas, cx: Float, type: TurretType) {
        val isSel = selected == type
        paint.color = Color.argb(if (isSel) 210 else 110, 30, 40, 60)
        canvas.drawCircle(cx, turretBtnCy, turretBtnR, paint)
        if (isSel) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            paint.color = Color.WHITE
            canvas.drawCircle(cx, turretBtnCy, turretBtnR, paint)
            paint.style = Paint.Style.FILL
        }
        // Dome icon.
        paint.color = if (type == TurretType.CANNON) Color.parseColor("#B0BEC5") else Color.parseColor("#FFD23F")
        canvas.drawCircle(cx, turretBtnCy + turretBtnR * 0.15f, turretBtnR * 0.5f, paint)
        // Cost label.
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = width * 0.03f
        textPaint.color = if (energy >= cost(type)) Color.WHITE else Color.parseColor("#FF8A80")
        canvas.drawText(fa(cost(type).toInt()), cx, turretBtnCy - turretBtnR * 0.45f, textPaint)
    }

    private fun drawOverlay(canvas: Canvas, title: String, line1: String, line2: String) {
        paint.color = Color.argb(140, 0, 0, 0)
        canvas.drawRect(0f, 0f, width, height, paint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.parseColor("#FFD23F")
        textPaint.textSize = width * 0.11f
        canvas.drawText(title, width / 2f, height * 0.40f, textPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = width * 0.05f
        canvas.drawText(line1, width / 2f, height * 0.50f, textPaint)
        canvas.drawText(line2, width / 2f, height * 0.58f, textPaint)
    }

    private fun buildStarfield() {
        stars.clear()
        repeat(110) {
            stars.add(floatArrayOf(Random.nextFloat() * width, Random.nextFloat() * height, Random.nextFloat() * 1.8f + 0.4f, Random.nextFloat()))
        }
    }

    private fun buildNebulas() {
        nebulas.clear()
        nebulas.add(floatArrayOf(width * 0.2f, height * 0.25f, width * 0.5f, 0f))
        nebulas.add(floatArrayOf(width * 0.85f, height * 0.7f, width * 0.58f, 1f))
        nebulas.add(floatArrayOf(width * 0.5f, height * 0.5f, width * 0.7f, 2f))
    }

    // ---- Input ----

    private fun uiHit(x: Float, y: Float): Ui = when {
        hypot(x - shockCx, y - shockCy) < shockR -> Ui.SHOCK
        hypot(x - rapidCx, y - turretBtnCy) < turretBtnR -> Ui.RAPID
        hypot(x - cannonCx, y - turretBtnCy) < turretBtnR -> Ui.CANNON
        else -> Ui.NONE
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        synchronized(gameLock) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragged = false
                activeUi = if (state == State.PLAYING) uiHit(event.x, event.y) else Ui.NONE
                if (activeUi == Ui.NONE && state == State.PLAYING) {
                    prevAngle = atan2(event.y - planet.cy, event.x - planet.cx)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - downX, event.y - downY) > 24f) dragged = true
                if (activeUi == Ui.NONE && state == State.PLAYING) {
                    val cur = atan2(event.y - planet.cy, event.x - planet.cx)
                    var delta = cur - prevAngle
                    while (delta > Math.PI) delta -= (2 * Math.PI).toFloat()
                    while (delta < -Math.PI) delta += (2 * Math.PI).toFloat()
                    planet.rotation += delta
                    prevAngle = cur
                }
            }
            MotionEvent.ACTION_UP -> {
                if (state != State.PLAYING) {
                    startGame()
                } else if (activeUi != Ui.NONE) {
                    if (uiHit(event.x, event.y) == activeUi) when (activeUi) {
                        Ui.SHOCK -> triggerShockwave()
                        Ui.RAPID -> selected = TurretType.RAPID
                        Ui.CANNON -> selected = TurretType.CANNON
                        Ui.NONE -> {}
                    }
                    activeUi = Ui.NONE
                } else if (!dragged) {
                    placeDefender(downX, downY)
                }
            }
        }
        }
        return true
    }

    private fun placeDefender(x: Float, y: Float) {
        if (energy < cost(selected) || defenders.size >= maxDefenders) return
        val world = atan2(y - planet.cy, x - planet.cx)
        val local = world - planet.rotation
        for (d in defenders) {
            var diff = (d.localAngle - local) % (2f * Math.PI.toFloat())
            while (diff > Math.PI) diff -= (2 * Math.PI).toFloat()
            while (diff < -Math.PI) diff += (2 * Math.PI).toFloat()
            if (kotlin.math.abs(diff) < 0.21f) return
        }
        defenders.add(Defender(local, selected).also { it.aim = world })
        energy -= cost(selected)
    }
}
