package com.kidsgames.orbitdefense

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
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
 * You defend a small planet at the centre of the screen. Slime-zombies drift in
 * from every direction. **Spin the planet** by dragging it to re-aim all your
 * turrets at once, and **tap** empty space to build a new turret on the surface
 * facing that way (costs energy). Keep the planet's health above zero.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private enum class State { READY, PLAYING, GAME_OVER }

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

    // Tuning.
    private val defenderCost = 20f
    private val defenderRange get() = width * 0.36f
    private val fireInterval = 0.5f
    private val shotDamage = 12f
    private val shotSpeed get() = width * 1.0f
    private val contactDamage = 8f
    private val maxDefenders = 18

    // Touch / spin state.
    private var prevAngle = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragged = false

    private val prefs = context.getSharedPreferences("orbitdefense", Context.MODE_PRIVATE)

    private val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
    private fun fa(n: Int): String = buildString {
        for (c in n.toString()) append(if (c in '0'..'9') persianDigits[c - '0'] else c)
    }

    init {
        holder.addCallback(this)
        isFocusable = true
        bestKills = prefs.getInt("best", 0)
    }

    // ---- Surface lifecycle ----

    override fun surfaceCreated(holder: SurfaceHolder) = resume()

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        width = w.toFloat()
        height = h.toFloat()
        bgShader = LinearGradient(
            0f, 0f, 0f, height,
            Color.parseColor("#0B1026"), Color.parseColor("#241A4B"),
            Shader.TileMode.CLAMP
        )
        planet = Planet(width / 2f, height / 2f, width * 0.15f)
        buildStarfield()
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

            if (state == State.PLAYING) update(dt)
            updateParticles(dt)

            val canvas = holder.lockCanvas() ?: continue
            try {
                synchronized(holder) { render(canvas) }
            } finally {
                holder.unlockCanvasAndPost(canvas)
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

        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            spawnEnemy()
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
                planet.hp -= contactDamage
                burst(e.x, e.y, Color.parseColor("#FF5252"), 14)
                ei.remove()
                if (planet.hp <= 0f) {
                    planet.hp = 0f
                    gameOver()
                }
            } else if (!e.alive) {
                ei.remove()
            }
        }

        // Turrets acquire the nearest enemy in range and fire.
        for (d in defenders) {
            if (d.cooldown > 0f) continue
            val target = nearestEnemy(d.x, d.y, defenderRange) ?: continue
            d.aim = atan2(target.y - d.y, target.x - d.x)
            val vx = cosf(d.aim) * shotSpeed
            val vy = sinf(d.aim) * shotSpeed
            shots.add(Projectile(d.x, d.y, vx, vy, shotDamage))
            d.cooldown = fireInterval
        }

        // Move shots; resolve hits.
        val si = shots.iterator()
        while (si.hasNext()) {
            val s = si.next()
            s.update(dt)
            if (s.x < -50 || s.x > width + 50 || s.y < -50 || s.y > height + 50) {
                si.remove(); continue
            }
            val hit = enemies.firstOrNull { it.alive && hypot(it.x - s.x, it.y - s.y) < it.radius + 8f }
            if (hit != null) {
                hit.hp -= s.damage
                burst(s.x, s.y, Color.parseColor("#4DD0E1"), 6)
                s.alive = false
                if (hit.hp <= 0f) {
                    hit.alive = false
                    kills++
                    energy = min(140f, energy + 6f)
                    burst(hit.x, hit.y, Color.parseColor("#7CB342"), 12)
                }
                si.remove()
            }
        }
        enemies.removeAll { !it.alive }
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

    private fun spawnEnemy() {
        val angle = Random.nextFloat() * (2f * Math.PI.toFloat())
        val dist = hypot(width, height) * 0.55f
        val speed = height * (0.045f + wave * 0.0045f)
        val hp = 16f + wave * 5f
        val r = width * 0.045f
        enemies.add(Enemy(angle, dist, speed, hp, r))
    }

    private fun burst(x: Float, y: Float, color: Int, count: Int) {
        repeat(count) {
            val a = Random.nextFloat() * (2f * Math.PI.toFloat())
            val sp = Random.nextFloat() * 260f + 60f
            particles.add(Particle(x, y, cosf(a) * sp, sinf(a) * sp, color))
        }
    }

    private fun updateParticles(dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.update(dt)
            if (p.life <= 0f) it.remove()
        }
    }

    // ---- Rendering ----

    private fun render(canvas: Canvas) {
        paint.shader = bgShader
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = null
        drawStarfield(canvas)

        if (::planet.isInitialized) {
            planet.draw(canvas, paint)
            for (d in defenders) d.draw(canvas, paint)
        }
        for (e in enemies) e.draw(canvas, paint)
        for (s in shots) s.draw(canvas, paint)
        for (p in particles) p.draw(canvas, paint)

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

    private fun drawHud(canvas: Canvas) {
        textPaint.textSize = width * 0.055f
        textPaint.color = Color.WHITE

        // RTL: kills top-right, wave top-left.
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(context.getString(R.string.hud_kills, fa(kills)), width * 0.95f, height * 0.07f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(context.getString(R.string.hud_wave, fa(wave)), width * 0.05f, height * 0.07f, textPaint)

        // Energy bar along the bottom with a Persian label.
        val barW = width * 0.9f
        val barX = width * 0.05f
        val barY = height * 0.94f
        val barH = height * 0.022f
        paint.color = Color.argb(80, 255, 255, 255)
        canvas.drawRoundRect(barX, barY, barX + barW, barY + barH, barH, barH, paint)
        paint.color = Color.parseColor("#FFD23F")
        canvas.drawRoundRect(barX, barY, barX + barW * (energy / 140f), barY + barH, barH, barH, paint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = width * 0.04f
        canvas.drawText(context.getString(R.string.hud_energy), width / 2f, barY - height * 0.012f, textPaint)
    }

    private fun drawOverlay(canvas: Canvas, title: String, line1: String, line2: String) {
        paint.color = Color.argb(130, 0, 0, 0)
        canvas.drawRect(0f, 0f, width, height, paint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.parseColor("#FFD23F")
        textPaint.textSize = width * 0.11f
        canvas.drawText(title, width / 2f, height * 0.40f, textPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = width * 0.05f
        canvas.drawText(line1, width / 2f, height * 0.50f, textPaint)
        textPaint.textSize = width * 0.05f
        canvas.drawText(line2, width / 2f, height * 0.58f, textPaint)
    }

    private fun drawStarfield(canvas: Canvas) {
        paint.color = Color.argb(160, 255, 255, 255)
        for (s in stars) canvas.drawCircle(s[0], s[1], s[2], paint)
    }

    private fun buildStarfield() {
        stars.clear()
        repeat(70) {
            stars.add(floatArrayOf(Random.nextFloat() * width, Random.nextFloat() * height, Random.nextFloat() * 2.5f + 0.5f))
        }
    }

    // ---- Input ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragged = false
                prevAngle = atan2(event.y - planet.cy, event.x - planet.cx)
            }
            MotionEvent.ACTION_MOVE -> {
                if (state == State.PLAYING) {
                    val cur = atan2(event.y - planet.cy, event.x - planet.cx)
                    var delta = cur - prevAngle
                    while (delta > Math.PI) delta -= (2 * Math.PI).toFloat()
                    while (delta < -Math.PI) delta += (2 * Math.PI).toFloat()
                    planet.rotation += delta
                    prevAngle = cur
                }
                if (hypot(event.x - downX, event.y - downY) > 24f) dragged = true
            }
            MotionEvent.ACTION_UP -> {
                if (state != State.PLAYING) {
                    startGame()
                } else if (!dragged) {
                    placeDefender(downX, downY)
                }
            }
        }
        return true
    }

    private fun placeDefender(x: Float, y: Float) {
        if (energy < defenderCost || defenders.size >= maxDefenders) return
        val world = atan2(y - planet.cy, x - planet.cx)
        val local = world - planet.rotation
        // Reject if too close to an existing turret (~12°).
        for (d in defenders) {
            var diff = (d.localAngle - local) % (2f * Math.PI.toFloat())
            while (diff > Math.PI) diff -= (2 * Math.PI).toFloat()
            while (diff < -Math.PI) diff += (2 * Math.PI).toFloat()
            if (kotlin.math.abs(diff) < 0.21f) return
        }
        defenders.add(Defender(local).also { it.aim = world })
        energy -= defenderCost
    }
}
