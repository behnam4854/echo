package com.kidsgames.starcatcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.sin

/** Kind of object that falls from the top of the screen. */
enum class ItemType { STAR, GEM, ASTEROID }

/**
 * The player-controlled basket that slides along the bottom of the screen.
 * Its horizontal centre follows the player's finger.
 */
class Basket(var cx: Float, val cy: Float, val width: Float, val height: Float) {

    val left get() = cx - width / 2f
    val right get() = cx + width / 2f
    val top get() = cy - height / 2f

    /** Move toward [targetX] smoothly so the basket glides instead of teleporting. */
    fun update(targetX: Float, minX: Float, maxX: Float) {
        val desired = targetX.coerceIn(minX, maxX)
        cx += (desired - cx) * 0.35f
    }

    fun draw(canvas: Canvas, paint: Paint) {
        // Cup body
        paint.color = Color.parseColor("#FF6B6B")
        canvas.drawRoundRect(left, top, right, top + height, 24f, 24f, paint)
        // Rim highlight
        paint.color = Color.parseColor("#FFA8A8")
        canvas.drawRoundRect(left, top, right, top + height * 0.32f, 24f, 24f, paint)
    }

    /** True if the falling item's centre has entered the mouth of the basket. */
    fun catches(item: FallingItem): Boolean {
        val withinX = item.x in left..right
        val withinY = item.y + item.radius >= top && item.y <= top + height
        return withinX && withinY
    }
}

/** A single object falling down the screen. */
class FallingItem(
    var x: Float,
    var y: Float,
    val radius: Float,
    val speed: Float,
    val type: ItemType,
    val spin: Float
) {
    var angle: Float = 0f
    var alive: Boolean = true

    fun update(dt: Float) {
        y += speed * dt
        angle += spin * dt
    }

    /** Points awarded for catching, or life cost (negative) for hazards. */
    val value: Int
        get() = when (type) {
            ItemType.STAR -> 1
            ItemType.GEM -> 5
            ItemType.ASTEROID -> 0
        }

    fun draw(canvas: Canvas, paint: Paint) {
        when (type) {
            ItemType.STAR -> drawStar(canvas, paint, 5, radius, radius * 0.45f, Color.parseColor("#FFD23F"))
            ItemType.GEM -> drawGem(canvas, paint)
            ItemType.ASTEROID -> drawAsteroid(canvas, paint)
        }
    }

    private fun drawStar(canvas: Canvas, paint: Paint, points: Int, outer: Float, inner: Float, color: Int) {
        paint.color = color
        val path = android.graphics.Path()
        val step = Math.PI / points
        var rot = angle - Math.PI / 2
        for (i in 0 until points * 2) {
            val r = if (i % 2 == 0) outer else inner
            val px = x + (r * cos(rot)).toFloat()
            val py = y + (r * sin(rot)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            rot += step
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawGem(canvas: Canvas, paint: Paint) {
        paint.color = Color.parseColor("#4DD0E1")
        val path = android.graphics.Path()
        path.moveTo(x, y - radius)
        path.lineTo(x + radius, y)
        path.lineTo(x, y + radius)
        path.lineTo(x - radius, y)
        path.close()
        canvas.drawPath(path, paint)
        paint.color = Color.parseColor("#B2EBF2")
        canvas.drawCircle(x - radius * 0.3f, y - radius * 0.2f, radius * 0.25f, paint)
    }

    private fun drawAsteroid(canvas: Canvas, paint: Paint) {
        paint.color = Color.parseColor("#8D6E63")
        canvas.drawCircle(x, y, radius, paint)
        paint.color = Color.parseColor("#6D4C41")
        canvas.drawCircle(x - radius * 0.35f, y - radius * 0.25f, radius * 0.22f, paint)
        canvas.drawCircle(x + radius * 0.3f, y + radius * 0.3f, radius * 0.18f, paint)
    }
}

/** Tiny burst particle used for catch feedback. */
class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val color: Int
) {
    var life: Float = 1f

    fun update(dt: Float) {
        x += vx * dt
        y += vy * dt
        vy += 900f * dt // gravity
        life -= dt * 1.6f
    }

    fun draw(canvas: Canvas, paint: Paint) {
        paint.color = (((life.coerceIn(0f, 1f) * 255).toInt()) shl 24) or (color and 0x00FFFFFF)
        canvas.drawCircle(x, y, 8f, paint)
    }
}
