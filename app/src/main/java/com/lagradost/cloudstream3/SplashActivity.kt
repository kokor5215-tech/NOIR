package com.lagradost.cloudstream3

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.lagradost.cloudstream3.ui.account.AccountSelectActivity
import com.lagradost.cloudstream3.utils.NoirChime
import com.lagradost.cloudstream3.utils.NoirHaptic
import com.lagradost.cloudstream3.utils.UIHelper.toPx

/**
 * NOIR — intro v5: LATAR HITAM POLOS (permintaan user).
 *
 * Estetika datang dari tipografi & gerak, bukan dari latar:
 *  1. Void hitam murni.
 *  2. Huruf N-O-I-R (font tipis Poppins-Thin) muncul stagger, naik & meluruh.
 *  3. Letter-spacing melebar seperti napas.
 *  4. Garis rambut melebar dari tengah + tagline redup.
 *  5. Denyut glow radial lembut sekali di belakang wordmark.
 *  6. Komposisi settle 1.03 -> 1, lalu meluruh ke beranda.
 *
 * ~2,8 dtk; satu sentuhan = skip; reduce-motion = fade sederhana.
 */
class SplashActivity : AppCompatActivity() {

    private var navigated = false
    private var root: FrameLayout? = null
    private val runningAnimators = mutableListOf<Animator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isTv = getSystemService(UI_MODE_SERVICE)
            .let { (it as? UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION }

        root = buildLayout(isTv).also { setContentView(it) }

        root?.setOnClickListener { goToMain() }

        NoirChime.play(this)

        if (reduceMotion()) {
            root?.postDelayed({ goToMain() }, 600)
            return
        }
        runIntro(isTv)
    }

    private fun reduceMotion(): Boolean = try {
        Settings.Global.getFloat(
            contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f || androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this)
            .getBoolean("noir_anim_key", false)
    } catch (_: Throwable) { false }

    // ------------------------------------------------------------------
    // Susunan tampilan — hitam polos
    // ------------------------------------------------------------------

    private fun buildLayout(isTv: Boolean): FrameLayout {
        val typeface: Typeface? =
            ResourcesCompat.getFont(this, R.font.noir_bold)

        // Besar & tegas ala wordmark Netflix — tebal, elegan, terbaca jauh.
        val wordSize = if (isTv) 76f else 56f
        val tagSize = if (isTv) 13f else 10f

        val letters = "NOIR".map { ch ->
            TextView(this).apply {
                text = ch.toString()
                textSize = wordSize
                setTextColor(Color.WHITE)
                if (typeface != null) setTypeface(typeface)
                alpha = 0f
                translationY = 16f.toPx
                gravity = Gravity.CENTER
            }
        }

        val wordRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            letters.forEach { addView(it) }
        }

        // Pembungkus wordmark: tempat glow berdenyut (ter-clip di sini).
        val wordWrap = FrameLayout(this).apply {
            clipChildren = true
            addView(wordRow)
        }

        // Denyut cahaya radial di belakang huruf — menyala lembut sekali.
        val glow = View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                colors = intArrayOf(0x40FFFFFF, 0x00FFFFFF)
                gradientRadius = 170f.toPx
            }
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        wordWrap.addView(glow)

        val hairline = View(this).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(0, 1.toPx).apply {
                topMargin = 18.toPx
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        val tagline = TextView(this).apply {
            text = "S T R E A M I N G"
            textSize = tagSize
            setTextColor(Color.parseColor("#8E8E93"))
            letterSpacing = 0.34f
            gravity = Gravity.CENTER
            alpha = 0f
            if (typeface != null) setTypeface(typeface)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = 16.toPx
            }
        }

        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(wordWrap)
            addView(hairline)
            addView(tagline)
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        }

        return FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(stack)
            tag = arrayOf(stack, glow, wordWrap)
        }
    }

    // ------------------------------------------------------------------
    // Animasi
    // ------------------------------------------------------------------

    private fun runIntro(isTv: Boolean) {
        val container = root ?: return
        val parts = container.tag as? Array<*> ?: return
        val stack = parts[0] as? LinearLayout ?: return
        val glow = parts[1] as? View ?: return
        val wordWrap = parts[2] as? FrameLayout ?: return
        val wordRow = wordWrap.getChildAt(0) as? LinearLayout ?: return
        val hairline = stack.getChildAt(1) ?: return
        val tagline = stack.getChildAt(2) as? TextView ?: return

        val letterViews = (0 until wordRow.childCount).map { wordRow.getChildAt(it) }

        // --- 1. Huruf muncul berurutan ---
        val letterAnims = letterViews.mapIndexed { i, v ->
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f).setDuration(520),
                    ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 16f.toPx, 0f).setDuration(620)
                )
                interpolator = DecelerateInterpolator(1.6f)
                startDelay = 180L + i * 110L
            }
        }

        // --- 2. Jarak antar huruf melebar pelan ---
        val tracking = ValueAnimator.ofFloat(0.01f, 0.14f).apply {
            duration = 1500
            startDelay = 180
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                letterViews.forEach { tv -> (tv as? TextView)?.letterSpacing = v }
            }
        }

        // --- 3. Garis rambut melebar dari tengah ---
        val targetWidth = (if (isTv) 150 else 104).toPx
        val lineGrow = ValueAnimator.ofInt(0, targetWidth).apply {
            duration = 900
            startDelay = 620
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                val w = it.animatedValue as Int
                hairline.layoutParams = (hairline.layoutParams as LinearLayout.LayoutParams).apply {
                    width = w
                }
                hairline.requestLayout()
            }
        }
        val lineFade = ObjectAnimator.ofFloat(hairline, View.ALPHA, 0f, 0.55f).apply {
            duration = 500
            startDelay = 620
        }

        // --- 4. Tagline menyusul, redup ---
        val tagFade = ObjectAnimator.ofFloat(tagline, View.ALPHA, 0f, 0.85f).apply {
            duration = 800
            startDelay = 1000
            interpolator = DecelerateInterpolator()
        }

        // --- 5. Denyut glow radial sekali, lembut ---
        val glowPulse = ObjectAnimator.ofFloat(glow, View.ALPHA, 0f, 0.5f, 0f).apply {
            duration = 1000
            startDelay = 1000
            interpolator = AccelerateDecelerateInterpolator()
        }

        // --- 6. Komposisi settle: skala 1.03 -> 1 ---
        val settle = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(stack, View.SCALE_X, 1.03f, 1f),
                ObjectAnimator.ofFloat(stack, View.SCALE_Y, 1.03f, 1f)
            )
            duration = 1000
            startDelay = 1150
            interpolator = DecelerateInterpolator(1.4f)
        }

        // --- 7. Meluruh, lalu pindah ---
        val fadeOut = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(stack, View.ALPHA, 1f, 0f)
            )
            duration = 450
            startDelay = 2300
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    goToMain()
                }
            })
        }

        val set = AnimatorSet().apply {
            playTogether(
                listOf<Animator>() + letterAnims +
                    listOf(tracking, lineGrow, lineFade, tagFade, glowPulse, settle, fadeOut)
            )
        }

        runningAnimators.add(set)
        set.start()

        // --- 8. MULTISENSORIK: hentakan haptic lembut saat "CAHAYA" berdenting.
        container.postDelayed({
            if (navigated) return@postDelayed
            NoirHaptic.thump(this)
        }, NoirChime.BELL_MS)
    }

    // ------------------------------------------------------------------
    // Navigasi
    // ------------------------------------------------------------------

    private fun goToMain() {
        if (navigated) return
        navigated = true

        runningAnimators.forEach {
            try {
                it.cancel()
            } catch (_: Exception) {
            }
        }
        runningAnimators.clear()
        NoirChime.stop()

        startActivity(Intent(this, AccountSelectActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        runningAnimators.forEach {
            try {
                it.cancel()
            } catch (_: Exception) {
            }
        }
        runningAnimators.clear()
        super.onDestroy()
    }
}
