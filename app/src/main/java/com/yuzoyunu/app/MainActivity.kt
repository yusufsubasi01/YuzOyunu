package com.yuzoyunu.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    companion object {
        const val TOTAL_ROUNDS = 8        // toplam tur sayısı
        const val ROUND_MS = 12_000L      // her turda süre
        const val HOLD_MS = 700L          // ifadeyi bu kadar süre tutmalısın
        const val THRESHOLD = 0.60f       // doğrulama eşiği (0..1)
        const val MODEL = "face_landmarker.task"
    }

    private lateinit var root: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("yuz_oyunu", Context.MODE_PRIVATE) }

    // Oyun durumu
    private var score = 0
    private var round = 0
    private var options: List<Emotion> = emptyList()
    private var target: Emotion? = null
    private var pendingEmotion: Emotion? = null

    // Kamera / yüz tespiti
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var landmarker: FaceLandmarker? = null
    private var previewView: PreviewView? = null

    // Tur durumu
    @Volatile private var roundActive = false
    private var holdStartMs = 0L
    private var roundEndMs = 0L

    // Kamera ekranı görünümleri
    private var timeText: TextView? = null
    private var feedbackText: TextView? = null
    private var holdBar: ProgressBar? = null
    private var banner: TextView? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val e = pendingEmotion
            pendingEmotion = null
            if (granted && e != null) {
                startChallenge(e)
            } else {
                Toast.makeText(this, "Kamera izni gerekli", Toast.LENGTH_LONG).show()
                if (round > 0) showCards() else showMenu()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        root = FrameLayout(this).apply { setBackgroundColor(0xFF14162B.toInt()) }
        setContentView(root)
        showMenu()
    }

    override fun onDestroy() {
        super.onDestroy()
        roundActive = false
        handler.removeCallbacksAndMessages(null)
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        landmarker?.close()
        landmarker = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (roundActive) abortRound() else super.onBackPressed()
    }

    // ------------------------------------------------------------------ Ekranlar

    private fun showMenu() {
        stopCamera()
        val best = prefs.getInt("best", 0)
        val col = column().apply { gravity = Gravity.CENTER }
        col.addView(text("🎭", 72f, Color.WHITE))
        col.addView(text("Yüz Oyunu", 34f, Color.WHITE, bold = true).margin(top = 12))
        col.addView(
            text(
                "Kartı seç, kameraya o yüz ifadesini yap,\nbaşarırsan puanı kap!",
                16f, 0xFFB8BCE0.toInt()
            ).apply { gravity = Gravity.CENTER }.margin(top = 12, bottom = 8)
        )
        col.addView(text("En yüksek puan: $best", 16f, 0xFFF5B301.toInt(), bold = true).margin(bottom = 32))
        col.addView(button("BAŞLA", 0xFF2ECC71.toInt()) {
            score = 0
            round = 0
            newRound()
        })
        setScreen(col)
    }

    private fun newRound() {
        round++
        options = Emotion.values().toList().shuffled().take(3)
        showCards()
    }

    private fun showCards() {
        stopCamera()
        val col = column().apply { setPadding(dp(20), dp(36), dp(20), dp(20)) }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(text("Tur $round/$TOTAL_ROUNDS", 16f, 0xFFB8BCE0.toInt(), bold = true), lp(0, wrap(), 1f))
        top.addView(text("Puan: $score", 18f, Color.WHITE, bold = true))
        col.addView(top, lp(match(), wrap()))
        col.addView(text("Bir kart seç!", 28f, Color.WHITE, bold = true).margin(top = 16, bottom = 16))

        options.forEach { e ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(e.color, 28)
                elevation = dp(6).toFloat()
                isClickable = true
                setOnClickListener { startChallenge(e) }
            }
            card.addView(text(e.emoji, 64f, Color.WHITE))
            card.addView(text(e.label, 24f, Color.WHITE, bold = true))
            val p = lp(match(), 0, 1f)
            p.setMargins(0, dp(8), 0, dp(8))
            col.addView(card, p)
        }
        setScreen(col)
    }

    private fun buildCameraScreen(e: Emotion) {
        val frame = FrameLayout(this)
        val pv = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        previewView = pv
        frame.addView(pv, FrameLayout.LayoutParams(match(), match()))

        // Üst panel
        val topPanel = column().apply {
            setBackgroundColor(0xB0000000.toInt())
            setPadding(dp(16), dp(32), dp(16), dp(12))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        topPanel.addView(text("${e.emoji}  ${e.label} yüzü yap!", 26f, Color.WHITE, bold = true))
        val tt = text("⏱ ${ROUND_MS / 1000}", 20f, 0xFFF5B301.toInt(), bold = true).margin(top = 4)
        timeText = tt
        topPanel.addView(tt)
        frame.addView(topPanel, FrameLayout.LayoutParams(match(), wrap(), Gravity.TOP))

        // Alt panel
        val bottom = column().apply {
            setBackgroundColor(0xB0000000.toInt())
            setPadding(dp(20), dp(16), dp(20), dp(24))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val fb = text("Yüzün aranıyor…", 16f, Color.WHITE)
        feedbackText = fb
        bottom.addView(fb)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(e.color)
        }
        holdBar = bar
        bottom.addView(bar, lp(match(), dp(10)).apply { setMargins(0, dp(10), 0, dp(10)) })
        bottom.addView(button("Vazgeç", 0xFF555A80.toInt()) { abortRound() })
        frame.addView(bottom, FrameLayout.LayoutParams(match(), wrap(), Gravity.BOTTOM))

        // Sonuç afişi
        val bn = text("", 40f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        banner = bn
        frame.addView(bn, FrameLayout.LayoutParams(match(), wrap(), Gravity.CENTER))

        setScreen(frame)
    }

    private fun showEnd() {
        stopCamera()
        val best = prefs.getInt("best", 0)
        val isRecord = score > best
        if (isRecord) prefs.edit().putInt("best", score).apply()

        val col = column().apply { gravity = Gravity.CENTER }
        col.addView(text(if (isRecord) "🏆" else "🎉", 72f, Color.WHITE))
        col.addView(text("Oyun bitti!", 32f, Color.WHITE, bold = true).margin(top = 8))
        col.addView(text("Puanın: $score", 28f, 0xFFF5B301.toInt(), bold = true).margin(top = 16))
        col.addView(
            text(
                if (isRecord) "Yeni rekor! 🔥" else "En yüksek puan: $best",
                16f, 0xFFB8BCE0.toInt()
            ).margin(top = 4, bottom = 32)
        )
        col.addView(button("TEKRAR OYNA", 0xFF2ECC71.toInt()) {
            score = 0
            round = 0
            newRound()
        })
        col.addView(button("Menü", 0xFF555A80.toInt()) { showMenu() }.margin(top = 12))
        setScreen(col)
    }

    // ------------------------------------------------------------------ Tur akışı

    private fun startChallenge(e: Emotion) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingEmotion = e
            permLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        if (!ensureLandmarker()) {
            showCards()
            return
        }
        target = e
        buildCameraScreen(e)
        holdStartMs = 0L
        roundEndMs = SystemClock.uptimeMillis() + ROUND_MS
        roundActive = true
        bindCamera()
        handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!roundActive) return
            val left = roundEndMs - SystemClock.uptimeMillis()
            if (left <= 0) {
                finishRound(false)
                return
            }
            timeText?.text = "⏱ ${(left + 999) / 1000}"
            handler.postDelayed(this, 100)
        }
    }

    private fun onFace(blend: Map<String, Float>?) {
        if (!roundActive) return
        val t = target ?: return
        if (blend == null) {
            holdStartMs = 0L
            holdBar?.progress = 0
            feedbackText?.text = "Yüzün görünmüyor, kameraya bak 👀"
            return
        }
        val s = EmotionScorer.scores(blend)
        val ts = s[t] ?: 0f
        val best = s.maxByOrNull { it.value }?.key
        val now = SystemClock.uptimeMillis()
        val pct = (ts * 100).toInt()

        if (ts >= THRESHOLD && best == t) {
            if (holdStartMs == 0L) holdStartMs = now
            val held = now - holdStartMs
            holdBar?.progress = (held * 100 / HOLD_MS).toInt().coerceAtMost(100)
            feedbackText?.text = "Harika, böyle kal! 🔥  (%$pct)"
            if (held >= HOLD_MS) finishRound(true)
        } else {
            holdStartMs = 0L
            holdBar?.progress = 0
            feedbackText?.text = "Eşleşme: %$pct — biraz daha abart!"
        }
    }

    private fun finishRound(success: Boolean) {
        if (!roundActive) return
        roundActive = false
        handler.removeCallbacks(tick)
        val secondsLeft = ((roundEndMs - SystemClock.uptimeMillis()) / 1000).coerceAtLeast(0)
        val points = if (success) 100 + (secondsLeft * 10).toInt() else 0
        score += points

        banner?.apply {
            visibility = View.VISIBLE
            text = if (success) "✅ Başardın!\n+$points puan" else "❌ Olmadı…\n+0 puan"
            background = rounded(if (success) 0xE62ECC71.toInt() else 0xE6D9432F.toInt(), 24)
        }
        handler.postDelayed({
            if (round >= TOTAL_ROUNDS) showEnd() else newRound()
        }, 1600)
    }

    private fun abortRound() {
        roundActive = false
        handler.removeCallbacks(tick)
        showCards()
    }

    // ------------------------------------------------------------------ Kamera + MediaPipe

    private fun ensureLandmarker(): Boolean {
        if (landmarker != null) return true
        return try {
            val base = BaseOptions.builder().setModelAssetPath(MODEL).build()
            val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(1)
                .setOutputFaceBlendshapes(true)
                .setResultListener { result, _ -> handleResult(result) }
                .setErrorListener { err ->
                    runOnUiThread {
                        Toast.makeText(this, "Hata: ${err.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                .build()
            landmarker = FaceLandmarker.createFromOptions(this, opts)
            true
        } catch (ex: Exception) {
            Toast.makeText(this, "Yüz modeli yüklenemedi: ${ex.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun handleResult(result: FaceLandmarkerResult) {
        val shapes = result.faceBlendshapes()
        val map: Map<String, Float>? =
            if (shapes.isPresent && shapes.get().isNotEmpty()) {
                shapes.get()[0].associate { it.categoryName() to it.score() }
            } else null
        runOnUiThread { onFace(map) }
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!roundActive) return@addListener
            val provider = future.get()
            cameraProvider = provider
            val pv = previewView ?: return@addListener

            val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(cameraExecutor) { proxy -> analyze(proxy) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this@MainActivity, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis
                )
            } catch (ex: Exception) {
                Toast.makeText(this, "Kamera açılamadı: ${ex.message}", Toast.LENGTH_LONG).show()
                abortRound()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        val lm = landmarker
        if (lm == null || !roundActive) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val w = proxy.width
        val h = proxy.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        proxy.use { bmp.copyPixelsFromBuffer(it.planes[0].buffer) }

        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, w, h, m, true)
        try {
            lm.detectAsync(BitmapImageBuilder(rotated).build(), SystemClock.uptimeMillis())
        } catch (_: Exception) {
            // kare atlandı
        }
    }

    private fun stopCamera() {
        roundActive = false
        handler.removeCallbacks(tick)
        cameraProvider?.unbindAll()
        previewView = null
        timeText = null
        feedbackText = null
        holdBar = null
        banner = null
    }

    // ------------------------------------------------------------------ UI yardımcıları

    private fun setScreen(v: View) {
        root.removeAllViews()
        root.addView(v, FrameLayout.LayoutParams(match(), match()))
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun match() = ViewGroup.LayoutParams.MATCH_PARENT
    private fun wrap() = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(match(), match())
        setPadding(dp(24), dp(24), dp(24), dp(24))
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(label: String, color: Int, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(color, 16)
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(match(), dp(56))
        }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun <T : View> T.margin(top: Int = 0, bottom: Int = 0): T {
        val p = (layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(
                if (this is Button) match() else wrap(), wrap()
            )
        p.setMargins(0, dp(top), 0, dp(bottom))
        layoutParams = p
        return this
    }
}
