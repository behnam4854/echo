package com.kidsgames.starcatcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.random.Random

/**
 * The whole game: a [SurfaceView] that runs a fixed-step update loop on a
 * background thread and renders a simple "catch the falling stars" arcade game.
 *
 * Designed for ages 7-13: one-finger drag controls, gentle ramping difficulty,
 * bright friendly art, and short rounds with an immediate "tap to play again".
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

    private lateinit var basket: Basket
    private val items = ArrayList<FallingItem>()
    private val particles = ArrayList<Particle>()
    private val stars = ArrayList<FloatArray>() // background twinkles: [x, y, r]

    private var state = State.READY
    private var score = 0
    private var lives = 3
    private var level = 1
    private var bestScore = 0

    private var fingerX = 0f
    private var spawnTimer = 0f
    private var lastFrameNs = 0L

    private val prefs = context.getSharedPreferences("starcatcher", Context.MODE_PRIVATE)

    // Persian (Eastern Arabic) digits so all numbers render in Farsi too.
    private val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    private fun fa(n: Int): String = buildString {
        for (c in n.toString()) append(if (c in '0'..'9') persianDigits[c - '0'] else c)
    }

    init {
        holder.addCallback(this)
        isFocusable = true
        bestScore = prefs.getInt("best", 0)
    }

    // ---- Surface lifecycle ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        resume()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        width = w.toFloat()
        height = h.toFloat()
        fingerX = width / 2f
        bgShader = LinearGradient(
            0f, 0f, 0f, height,
            Color.parseColor("#0B1026"), Color.parseColor("#1B2A6B"),
            Shader.TileMode.CLAMP
        )
        val basketW = width * 0.22f
        val basketH = basketW * 0.55f
        basket = Basket(width / 2f, height - basketH * 1.6f, basketW, basketH)
        buildStarfield()
        if (state == State.PLAYING) {
            // Surface resized mid-game; keep state but reset transient layout.
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        pauseGame()
    }

    fun resume() {
        if (running) return
        running = true
        lastFrameNs = System.nanoTime()
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
        score = 0
        lives = 3
        level = 1
        spawnTimer = 0f
        items.clear()
        particles.clear()
        state = State.PLAYING
    }

    private fun gameOver() {
        state = State.GAME_OVER
        if (score > bestScore) {
            bestScore = score
            prefs.edit().putInt("best", bestScore).apply()
        }
    }

    // ---- Main loop ----

    override fun run() {
        while (running) {
            val now = System.nanoTime()
            var dt = (now - lastFrameNs) / 1_000_000_000f
            lastFrameNs = now
            if (dt > 0.05f) dt = 0.05f // clamp big stalls so nothing tunnels through

            if (state == State.PLAYING) update(dt)
            updateParticles(dt)

            val canvas = holder.lockCanvas() ?: continue
            try {
                synchronized(holder) { render(canvas) }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }

            // Aim for ~60fps without busy-spinning.
            val frameMs = (System.nanoTime() - now) / 1_000_000
            val sleep = 16 - frameMs
            if (sleep > 0) try {
                Thread.sleep(sleep)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun update(dt: Float) {
        level = 1 + score / 15
        basket.update(fingerX, basket.width / 2f, width - basket.width / 2f)

        // Spawn cadence and fall speed both scale gently with level.
        val spawnInterval = (0.95f - level * 0.04f).coerceAtLeast(0.35f)
        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            spawnItem()
            spawnTimer = spawnInterval
        }

        val it = items.iterator()
        while (it.hasNext()) {
            val item = it.next()
            item.update(dt)
            if (item.alive && basket.catches(item)) {
                item.alive = false
                onCatch(item)
                it.remove()
            } else if (item.y - item.radius > height) {
                // Missing a good item is harmless; only asteroids hurt on catch.
                it.remove()
            }
        }
    }

    private fun onCatch(item: FallingItem) {
        when (item.type) {
            ItemType.ASTEROID -> {
                lives--
                burst(item.x, item.y, Color.parseColor("#8D6E63"))
                if (lives <= 0) gameOver()
            }
            else -> {
                score += item.value
                burst(item.x, item.y, if (item.type == ItemType.GEM) Color.parseColor("#4DD0E1") else Color.parseColor("#FFD23F"))
            }
        }
    }

    private fun spawnItem() {
        val r = width * 0.045f
        val x = Random.nextFloat() * (width - 2 * r) + r
        val baseSpeed = height * (0.28f + level * 0.025f)
        val roll = Random.nextFloat()
        val type = when {
            roll < 0.20f + level * 0.015f -> ItemType.ASTEROID
            roll < 0.30f -> ItemType.GEM
            else -> ItemType.STAR
        }
        val speed = baseSpeed * if (type == ItemType.GEM) 1.25f else 1f
        val spin = (Random.nextFloat() - 0.5f) * 6f
        items.add(FallingItem(x, -r, r, speed, type, spin))
    }

    private fun burst(x: Float, y: Float, color: Int) {
        repeat(12) {
            val a = Random.nextFloat() * (2 * Math.PI).toFloat()
            val sp = Random.nextFloat() * 320f + 80f
            particles.add(
                Particle(x, y, (Math.cos(a.toDouble()) * sp).toFloat(), (Math.sin(a.toDouble()) * sp).toFloat() - 120f, color)
            )
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
        for (item in items) item.draw(canvas, paint)
        if (::basket.isInitialized) basket.draw(canvas, paint)
        for (p in particles) p.draw(canvas, paint)

        when (state) {
            State.READY -> drawCenteredScreen(
                canvas,
                context.getString(R.string.game_title),
                context.getString(R.string.tap_to_start),
                context.getString(R.string.ready_best, fa(bestScore))
            )
            State.PLAYING -> drawHud(canvas)
            State.GAME_OVER -> drawCenteredScreen(
                canvas,
                context.getString(R.string.game_over),
                context.getString(R.string.over_summary, fa(score), fa(bestScore)),
                context.getString(R.string.tap_to_restart)
            )
        }
    }

    private fun drawHud(canvas: Canvas) {
        textPaint.textSize = width * 0.06f
        textPaint.color = Color.WHITE

        // Persian reads right-to-left, so the score sits in the top-right corner
        // and the level on the left.
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(context.getString(R.string.hud_score, fa(score)), width * 0.96f, height * 0.07f, textPaint)

        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(context.getString(R.string.hud_level, fa(level)), width * 0.04f, height * 0.07f, textPaint)

        // Lives as little hearts, under the score on the right, filling leftward.
        paint.color = Color.parseColor("#FF6B6B")
        val hr = width * 0.025f
        for (i in 0 until lives) {
            val hx = width * 0.96f - i * (hr * 2.6f) - hr
            val hy = height * 0.11f
            canvas.drawCircle(hx - hr * 0.45f, hy, hr * 0.6f, paint)
            canvas.drawCircle(hx + hr * 0.45f, hy, hr * 0.6f, paint)
            canvas.drawRect(hx - hr * 0.9f, hy, hx + hr * 0.9f, hy + hr * 0.9f, paint)
        }
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawCenteredScreen(canvas: Canvas, title: String, line1: String, line2: String) {
        if (state != State.PLAYING) {
            paint.color = Color.argb(120, 0, 0, 0)
            canvas.drawRect(0f, 0f, width, height, paint)
        }
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.parseColor("#FFD23F")
        textPaint.textSize = width * 0.12f
        canvas.drawText(title, width / 2f, height * 0.40f, textPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = width * 0.06f
        canvas.drawText(line1, width / 2f, height * 0.50f, textPaint)
        textPaint.textSize = width * 0.055f
        canvas.drawText(line2, width / 2f, height * 0.58f, textPaint)
    }

    private fun buildStarfield() {
        stars.clear()
        repeat(60) {
            stars.add(floatArrayOf(Random.nextFloat() * width, Random.nextFloat() * height, Random.nextFloat() * 2.5f + 0.5f))
        }
    }

    private fun drawStarfield(canvas: Canvas) {
        paint.color = Color.argb(160, 255, 255, 255)
        for (s in stars) canvas.drawCircle(s[0], s[1], s[2], paint)
    }

    // ---- Input ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingerX = event.x
                if (state == State.READY || state == State.GAME_OVER) startGame()
            }
            MotionEvent.ACTION_MOVE -> fingerX = event.x
        }
        return true
    }
}
