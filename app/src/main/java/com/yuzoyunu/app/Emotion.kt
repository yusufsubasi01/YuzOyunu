package com.yuzoyunu.app

enum class Emotion(val label: String, val emoji: String, val color: Int) {
    MUTLU("Mutlu", "😀", 0xFFF5B301.toInt()),
    UZGUN("Üzgün", "😢", 0xFF3D7FD1.toInt()),
    SASKIN("Şaşkın", "😲", 0xFF9B59D0.toInt()),
    SINIRLI("Sinirli", "😠", 0xFFD9432F.toInt())
}

/**
 * MediaPipe Face Landmarker'ın verdiği blendshape değerlerinden (0..1)
 * her duygu için 0..1 arası bir benzerlik skoru üretir.
 * Eşikleri/ağırlıkları kendi yüzüne göre buradan ayarlayabilirsin.
 */
object EmotionScorer {

    fun scores(b: Map<String, Float>): Map<Emotion, Float> {
        fun g(k: String) = b[k] ?: 0f
        fun avg(l: String, r: String) = (g(l) + g(r)) / 2f
        fun c(x: Float) = x.coerceIn(0f, 1f)

        val smile = avg("mouthSmileLeft", "mouthSmileRight")
        val frown = avg("mouthFrownLeft", "mouthFrownRight")
        val browDown = avg("browDownLeft", "browDownRight")
        val browInner = g("browInnerUp")
        val browOuter = avg("browOuterUpLeft", "browOuterUpRight")
        val jawOpen = g("jawOpen")
        val eyeWide = avg("eyeWideLeft", "eyeWideRight")
        val sneer = avg("noseSneerLeft", "noseSneerRight")
        val press = avg("mouthPressLeft", "mouthPressRight")
        val squint = avg("eyeSquintLeft", "eyeSquintRight")
        val chin = g("mouthShrugLower")

        val noSmile3 = 1f - c(smile * 3f)
        val noSmile2 = 1f - c(smile * 2f)

        val happy = c(smile / 0.55f)

        val raised = maxOf(browInner, browOuter, eyeWide)
        val surprised = c(c(jawOpen / 0.35f) * 0.5f + c(raised / 0.5f) * 0.5f) * noSmile2

        val sad = c(
            c(frown / 0.35f) * 0.35f +
                c(browInner / 0.45f) * 0.4f +
                c(chin / 0.4f) * 0.25f
        ) * noSmile3

        val angry = c(
            c(browDown / 0.5f) * 0.55f +
                c(sneer / 0.35f) * 0.15f +
                c(press / 0.35f) * 0.15f +
                c(squint / 0.45f) * 0.15f
        ) * noSmile3

        return mapOf(
            Emotion.MUTLU to happy,
            Emotion.UZGUN to sad,
            Emotion.SASKIN to surprised,
            Emotion.SINIRLI to angry
        )
    }
}
