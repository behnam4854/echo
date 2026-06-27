package com.kidsgames.orbitdefense

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

// kotlin.math trig only takes Double; small helpers keep the call sites tidy.
fun cosf(a: Float): Float = cos(a.toDouble()).toFloat()
fun sinf(a: Float): Float = sin(a.toDouble()).toFloat()

/**
 * The little world the player defends. It sits at the centre of the screen and
 * can be spun by the player; [rotation] is added to every defender's angle so
 * spinning the planet re-aims all of its guns at once.
 */
class Planet(val cx: Float, val cy: Float, val radius: Float) {
    var rotation = 0f
    var hp = 120f
    val maxHp = 120f

    private val ring = RectF()

    fun draw(canvas: Canvas, paint: Paint) {
        // Soft atmosphere glow.
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(60, 90, 180, 230)
        canvas.drawCircle(cx, cy, radius * 1.25f, paint)

        // Planet body + a lighter highlight for a round, lit look.
        paint.color = Color.parseColor("#2E8B8F")
        canvas.drawCircle(cx, cy, radius, paint)
        paint.color = Color.parseColor("#3FB0A0")
        canvas.drawCircle(cx - radius * 0.28f, cy - radius * 0.28f, radius * 0.62f, paint)

        // A couple of darker "continents" that spin with the planet.
        paint.color = Color.parseColor("#1F6B6E")
        for (i in 0 until 3) {
            val a = rotation + i * 2.1f
            val rr = radius * (0.30f + 0.05f * i)
            canvas.drawCircle(cx + cosf(a) * radius * 0.4f, cy + sinf(a) * radius * 0.45f, rr, paint)
        }

        // Health ring around the planet.
        val frac = (hp / maxHp).coerceIn(0f, 1f)
        ring.set(cx - radius * 1.12f, cy - radius * 1.12f, cx + radius * 1.12f, cy + radius * 1.12f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.10f
        paint.color = Color.argb(70, 255, 255, 255)
        canvas.drawArc(ring, 0f, 360f, false, paint)
        paint.color = when {
            frac > 0.5f -> Color.parseColor("#7BE06B")
            frac > 0.25f -> Color.parseColor("#FFD23F")
            else -> Color.parseColor("#FF5252")
        }
        canvas.drawArc(ring, -90f, 360f * frac, false, paint)
        paint.style = Paint.Style.FILL
    }
}

/**
 * An auto-firing turret stuck to the planet's surface. Its [localAngle] is fixed
 * to the planet, so when the planet spins, the turret moves with it.
 */
class Defender(val localAngle: Float) {
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
        // Base dome.
        paint.color = Color.parseColor("#FFD23F")
        canvas.drawCircle(x, y, 22f, paint)
        // Barrel pointing along the current aim (outward by default).
        paint.color = Color.parseColor("#FFF1B8")
        val bx = x + cosf(aim) * 30f
        val by = y + sinf(aim) * 30f
        paint.strokeWidth = 12f
        paint.style = Paint.Style.STROKE
        canvas.drawLine(x, y, bx, by, paint)
        paint.style = Paint.Style.FILL
    }
}

/** A slime-zombie that drifts straight in toward the planet from its spawn angle. */
class Enemy(val angle: Float, var dist: Float, val speed: Float, var hp: Float, val radius: Float) {
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

    fun draw(canvas: Canvas, paint: Paint) {
        val r = radius + sinf(wobble) * 2f
        paint.color = Color.parseColor("#7CB342")
        canvas.drawCircle(x, y, r, paint)
        paint.color = Color.parseColor("#558B2F")
        canvas.drawCircle(x, y + r * 0.45f, r * 0.55f, paint)
        // Eyes.
        paint.color = Color.WHITE
        canvas.drawCircle(x - r * 0.35f, y - r * 0.15f, r * 0.26f, paint)
        canvas.drawCircle(x + r * 0.35f, y - r * 0.15f, r * 0.26f, paint)
        paint.color = Color.BLACK
        canvas.drawCircle(x - r * 0.30f, y - r * 0.10f, r * 0.12f, paint)
        canvas.drawCircle(x + r * 0.40f, y - r * 0.10f, r * 0.12f, paint)
    }
}

/** A shot fired by a defender. Flies straight; damages the first enemy it touches. */
class Projectile(var x: Float, var y: Float, val vx: Float, val vy: Float, val damage: Float) {
    var alive = true

    fun update(dt: Float) {
        x += vx * dt
        y += vy * dt
    }

    fun draw(canvas: Canvas, paint: Paint) {
        paint.color = Color.argb(90, 77, 208, 225)
        canvas.drawCircle(x, y, 12f, paint)
        paint.color = Color.parseColor("#B2EBF2")
        canvas.drawCircle(x, y, 6f, paint)
    }
}

/** Tiny burst particle for hit / death / damage feedback. */
class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Int) {
    var life = 1f

    fun update(dt: Float) {
        x += vx * dt
        y += vy * dt
        life -= dt * 1.6f
    }

    fun draw(canvas: Canvas, paint: Paint) {
        paint.color = (((life.coerceIn(0f, 1f) * 255).toInt()) shl 24) or (color and 0x00FFFFFF)
        canvas.drawCircle(x, y, 7f, paint)
    }
}
