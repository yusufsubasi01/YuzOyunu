# Yüz Oyunu (Android)

Kartlardan birini seç → ön kamera açılır → o yüz ifadesini yap (Mutlu / Üzgün / Şaşkın / Sinirli) →
yaklaşık 0,7 sn tutarsan doğrulanır ve puan kazanırsın. 8 tur, her turda 3 rastgele kart.
Puan = 100 + kalan saniye × 10.

Teknoloji: Kotlin, CameraX, MediaPipe Face Landmarker (blendshape'ler). İnternet gerekmez
(model ilk derlemede indirilip APK'nın içine gömülür).

## APK'yı alma

### Yol 1 — GitHub Actions (bilgisayarda kurulum yok)
1. Bu klasörü yeni bir GitHub deposuna yükle (`main` dalına).
2. Depoda **Actions → APK Derle** çalışır (ya da "Run workflow" ile elle başlat).
3. Bittiğinde çalışmanın altındaki **Artifacts → yuz-oyunu-apk** dosyasını indir, zip'ten çıkan
   `app-debug.apk`'yı telefona kurup aç (bilinmeyen kaynaklara izin vermen gerekir).

### Yol 2 — Android Studio
1. Android Studio ile klasörü aç, Gradle senkronizasyonunu bekle.
2. **Build → Build APK(s)**. Çıktı: `app/build/outputs/apk/debug/app-debug.apk`.
   (Komut satırı: `./gradlew assembleDebug`)

## Ayarlar (MainActivity.kt / Emotion.kt)
- `TOTAL_ROUNDS`, `ROUND_MS`, `HOLD_MS`, `THRESHOLD`: tur sayısı, süre, tutma süresi, hassasiyet.
- Bir ifade zor tanınıyorsa `Emotion.kt` içindeki `EmotionScorer` ağırlıklarını/bölenlerini değiştir
  (ör. `THRESHOLD`'u 0.5'e indir).
