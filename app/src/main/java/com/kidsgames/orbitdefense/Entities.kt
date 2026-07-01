package com.kidsgames.orbitdefense

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin

// kotlin.math trig only takes Double; small helpers keep the call sites tidy.
fun cosf(a: Float): Float = cos(a.toDouble()).toFloat()
fun sinf(a: Float): Float = sin(a.toDouble()).toFloat()

/** The two kinds of turret the player can build. */
enum class TurretType { RAPID, CANNON }

/** Enemy kinds. BOSS appears every few waves. */
enum class EnemyType { NORMAL, FAST, TANK, BOSS }

/**
 * The little world the player defends. It sits at the centre of the screen and
 * can be spun; [rotation] is added to every defender's angle so spinning the
 * planet re-aims all of its guns at once.
 */
class Planet(val cx: Float, val cy: Float, val radius: Float) {
    var rotation = 0f
    var hp = 120f
    val maxHp = 120f

    private val ring = RectF(cx - radius * 1.16f, cy - radius * 1.16f, cx + radius * 1.16f, cy + radius * 1.16f)

    // Cached shaders (positions are fixed, so build once).
    private val bodyShader: Shader = RadialGradient(
        cx - radius * 0.35f, cy - radius * 0.35f, radius * 1.3f,
        intArrayOf(Color.parseColor("#7FE6D6"), Color.parseColor("#33A39A"), Color.parseColor("#0F3E44")),
        floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
    )
    private val atmosphereShader: Shader = RadialGradient(
        cx, cy, radius * 1.6f,
        intArrayOf(Color.TRANSPARENT, Color.argb(0, 80, 200, 230), Color.argb(95, 80, 200, 230), Color.TRANSPARENT),
        floatArrayOf(0f, 0.55f, 0.8f, 1f), Shader.TileMode.CLAMP
    )

    fun draw(canvas: Canvas, paint: Paint) {
        // Atmosphere glow.
        paint.shader = atmosphereShader
        canvas.drawCircle(cx, cy, radius * 1.6f, paint)
        paint.shader = null

        // Shaded sphere body.
        paint.shader = bodyShader
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null

        // Continents that spin with the planet (kept well inside the disc).
        paint.color = Color.parseColor("#1C6B60")
        for (i in 0 until 3) {
            val a = rotation + i * 2.1f
            val rr = radius * (0.24f + 0.05f * i)
            canvas.drawCircle(cx + cosf(a) * radius * 0.38f, cy + sinf(a) * radius * 0.4f, rr, paint)
        }

        // Top-left rim highlight.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = Color.argb(80, 255, 255, 255)
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, 200f, 90f, false, paint)

        // Glowing health ring.
        val frac = (hp / maxHp).coerceIn(0f, 1f)
        paint.strokeWidth = radius * 0.09f
        paint.color = Color.argb(45, 255, 255, 255)
        canvas.drawArc(ring, 0f, 360f, false, paint)
        val col = when {
            frac > 0.5f -> Color.parseColor("#7BE06B")
            frac > 0.25f -> Color.parseColor("#FFD23F")
            else -> Color.parseColor("#FF5252")
        }
        paint.setShadowLayer(radius * 0.22f, 0f, 0f, col)
        paint.color = col
        canvas.drawArc(ring, -90f, 360f * frac, false, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
    }
}

/**
 * An auto-firing turret stuck to the planet's surface. Its [localAngle] is fixed
 * to the planet, so when the planet spins, the turret moves with it.
 */
class Defender(val localAngle: Float, val type: TurretType) {
    var cooldown = 0f
    var x = 0f
    var y = 0f
    var aim = 0f

    fun position(planet: Planet) {
        val a = localAngle + planet.rotation
        x = planet.cx + cosf(a) * planet.radius
        y = planet.cy + sinf(a) * planet.radius
    }

    fun draw(canvas: Canvas, paint: Paint) {
        val barrelLen = if (type == TurretType.CANNON) 40f else 30f
        val domeColor = if (type == TurretType.CANNON) "#B0BEC5" else "#FFD23F"
        val glow = if (type == TurretType.CANNON) Color.parseColor("#CFD8DC") else Color.parseColor("#FFD23F")

        // Barrel.
        paint.strokeCap = Paint.Cap.ROUND
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (type == TurretType.CANNON) 16f else 12f
        paint.color = Color.parseColor("#CFD6E0")
        canvas.drawLine(x, y, x + cosf(aim) * barrelLen, y + sinf(aim) * barrelLen, paint)
        paint.style = Paint.Style.FILL

        // Glowing dome.
        paint.setShadowLayer(16f, 0f, 0f, glow)
        paint.color = Color.parseColor(domeColor)
        canvas.drawCircle(x, y, if (type == TurretType.CANNON) 22f else 20f, paint)
        paint.clearShadowLayer()
        paint.color = Color.argb(200, 255, 255, 255)
        canvas.drawCircle(x - 6f, y - 6f, 5f, paint)
    }
}

/** A slime-zombie that drifts straight in toward the planet from its spawn angle. */
class Enemy(
    val type: EnemyType,
    val angle: Float,
    var dist: Float,
    val speed: Float,
    var hp: Float,
    val radius: Float,
    val contactDamage: Float,
    val reward: Float
) {
    var x = 0f
    var y = 0f
    var alive = true
    private var wobble = 0f

    fun update(dt: Float, cx: Float, cy: Float) {
        dist -= speed * dt
        wobble += dt * 6f
        x = cx + cosf(angle) * dist
        y = cy + sinf(angle) * dist
    }

    private fun bodyColor() = when (type) {
        EnemyType.NORMAL -> "#7CB342"
        EnemyType.FAST -> "#FFA726"
        EnemyType.TANK -> "#7E57C2"
        EnemyType.BOSS -> "#C62828"
    }

    private fun glowColor() = when (type) {
        EnemyType.NORMAL -> Color.parseColor("#7CB342")
        EnemyType.FAST -> Color.parseColor("#FFB74D")
        EnemyType.TANK -> Color.parseColor("#9575CD")
        EnemyType.BOSS -> Color.parseColor("#EF5350")
    }

    fun draw(canvas: Canvas, paint: Paint) {
        val r = radius + sinf(wobble) * (radius * 0.06f)

        // Soft ground shadow.
        paint.color = Color.argb(70, 0, 0, 0)
        canvas.drawOval(x - r * 0.8f, y + r * 0.7f, x + r * 0.8f, y + r * 1.02f, paint)

        // Glowing body.
        paint.setShadowLayer(r * 0.45f, 0f, 0f, glowColor())
        paint.color = Color.parseColor(bodyColor())
        canvas.drawCircle(x, y, r, paint)
        paint.clearShadowLayer()

        // Gloss highlight.
        paint.color = Color.argb(70, 255, 255, 255)
        canvas.drawCircle(x - r * 0.3f, y - r * 0.35f, r * 0.32f, paint)

        // Eyes.
        paint.color = Color.WHITE
        canvas.drawCircle(x - r * 0.33f, y - r * 0.1f, r * 0.26f, paint)
        canvas.drawCircle(x + r * 0.33f, y - r * 0.1f, r * 0.26f, paint)
        paint.color = Color.parseColor("#1A1A1A")
        canvas.drawCircle(x - r * 0.28f, y - r * 0.05f, r * 0.12f, paint)
        canvas.drawCircle(x + r * 0.38f, y - r * 0.05f, r * 0.12f, paint)
    }
}

/** A shot fired by a defender. Flies straight; damages the first enemy it touches. */
class Projectile(
    var x: Float,
    var y: Float,
    val vx: Float,
    val vy: Float,
    val damage: Float,
    val splash: Float,
    val color: Int,
    val radius: Float
) {
    var alive = true

    fun update(dt: Float) {
        x += vx * dt
        y += vy * dt
    }

    fun draw(canvas: Canvas, paint: Paint) {
        paint.setShadowLayer(radius * 1.4f, 0f, 0f, color)
        paint.color = color
        canvas.drawCircle(x, y, radius, paint)
        paint.clearShadowLayer()
        paint.color = Color.argb(230, 255, 255, 255)
        canvas.drawCircle(x, y, radius * 0.5f, paint)
    }
}

/** Tiny burst particle for hit / death / damage feedback. */
class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Int, private val size: Float) {
    var life = 1f

    fun update(dt: Float) {
        x += vx * dt
        y += vy * dt
        life -= dt * 1.6f
    }

    fun draw(canvas: Canvas, paint: Paint) {
        paint.color = (((life.coerceIn(0f, 1f) * 255).toInt()) shl 24) or (color and 0x00FFFFFF)
        canvas.drawCircle(x, y, size, paint)
    }
}
