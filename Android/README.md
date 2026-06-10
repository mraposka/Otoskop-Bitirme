# Otoskop — ESP32 Tabanlı Otonom Teleskop Mobil Uygulaması

Android Studio projesi (Kotlin, AGP 9.0.0). MVVM + Navigation + Retrofit/OkHttp +
Coroutines mimarisinde modüler yazılmıştır.

## Mimari

```
app/src/main/java/com/kou/otoskop/
├── OtoskopApp.kt                    # Application + servis locator
├── MainActivity.kt                  # Navigation host + runtime izin
├── core/
│   ├── AppConfig.kt                 # IP, port, timeout, polling sabitleri
│   ├── AppError.kt                  # Hata türleri (UI mesajına map'lenir)
│   ├── Resource.kt                  # Success / Failure sealed class
│   └── AstroMath.kt                 # azimuth wrap-around, yön etiketleri
├── data/
│   ├── model/                       # JSON DTO'ları (Moshi)
│   │   ├── TelescopeStatus.kt
│   │   ├── CelestialObject.kt
│   │   ├── VerifyResult.kt
│   │   ├── SkyArea.kt
│   │   └── PhoneSensorData.kt
│   ├── network/                     # Retrofit API'ları + factory
│   │   ├── Esp32Api.kt
│   │   ├── BackendApi.kt
│   │   ├── NetworkFactory.kt
│   │   └── EndpointRewriteInterceptor.kt
│   └── repository/
│       ├── Esp32Repository.kt       # status/move/target/correction/calibrate
│       ├── BackendRepository.kt     # observable-objects + verify-image
│       └── SensorRepository.kt      # FusedLocation + rotation vector
└── ui/
    ├── shared/                      # 4 ViewModel (paylaşılan)
    │   ├── ConnectionViewModel.kt
    │   ├── SensorViewModel.kt
    │   ├── TelescopeViewModel.kt
    │   └── ObjectsViewModel.kt
    ├── widget/                      # Custom view'lar
    │   ├── CompassView.kt           # döner kompas (Canvas)
    │   ├── MjpegView.kt             # MJPEG byte-stream parser
    │   ├── StatusChipView.kt
    │   └── AppErrorView.kt
    ├── connection/ConnectionFragment.kt
    ├── livecamera/LiveCameraFragment.kt
    ├── skyarea/SkyAreaFragment.kt
    ├── objectlist/
    │   ├── ObjectListFragment.kt
    │   └── CelestialObjectAdapter.kt
    └── control/TelescopeControlFragment.kt
```

### Tasarım kararları

- **Servis Locator**: `OtoskopApp` üzerinden tek `Esp32Repository`,
  `BackendRepository`, `SensorRepository` instance'ı; Hilt/Koin yerine
  manuel DI bu boyut için yeterli ve test edilebilir.
- **Endpoint rewrite interceptor**: kullanıcı bağlantı ekranında IP
  değiştirdiğinde Retrofit'i yeniden inşa etmiyoruz — OkHttp interceptor
  her isteğin host/port'unu rewrite ediyor.
- **`Resource<T>`**: `try/catch` her yerde dağılmasın diye Repository'ler
  `Resource.Success` veya `Resource.Failure(AppError)` döner.
- **Native MJPEG parser**: 3. parti widget yok; `MjpegView` JPEG SOI/EOI
  marker'larına göre frame çıkarır.
- **`activityViewModels()`**: 5 fragment arasında `TelescopeViewModel`,
  `ObjectsViewModel`, `SensorViewModel`, `ConnectionViewModel` paylaşılır.

## Gradle / Bağımlılıklar

Tek dokunuş gereksinimi (Android Studio "Sync Now"): değişiklikler
`gradle/libs.versions.toml`, `build.gradle.kts`, `app/build.gradle.kts`'da.

Eklenenler:

- Kotlin 2.0.21 plugin
- Coroutines (`org.jetbrains.kotlinx:kotlinx-coroutines-android`)
- Lifecycle / Fragment / Activity / Navigation (`androidx.navigation:*`)
- RecyclerView
- Retrofit + Moshi converter, OkHttp + logging-interceptor
- Play Services Location (FusedLocation)
- MockWebServer + coroutines-test (test bağımlılıkları)

## Gömülü taraf (Arduino Mega + ESP32-CAM)

Firmware proje kökündeki **[`../firmware/`](../firmware/README.md)** klasöründedir:

- **`firmware/mega_otoskop/`** (Arduino Mega 2560): MPU9250 (tilt-kompanze pusula +
  ivmeden yükseklik), GPS (TinyGPSPlus), 2 servo, complementary filter.
- **`firmware/esp32cam_otoskop/`** (AI-Thinker ESP32-CAM): Wi-Fi AP, HTTP API
  (`/status`, `/target`, `/move`, `/correction`, `/calibrate`, `/camera`, `/stream`,
  `/gps`), kamera ve Mega ile **UART JSON köprüsü**.

Akış: `Android ──WiFi/HTTP──► ESP32-CAM ──UART(JSON)──► Arduino Mega`. App yalnız
ESP ile konuşur; ESP, Mega'nın telemetrisini `/status` olarak servis eder. Kablolama
ve haberleşme protokolü için `firmware/README.md`.

## Gök cismi listesi + AI doğrulama (backend'siz)

Bu sürümde ayrı bir backend sunucu **yoktur**; app üçüncü-parti servisleri doğrudan kullanır:

- **Gözlemlenebilir objeler** → [Visible Planets API](https://api.visibleplanets.dev)
  (ücretsiz, anahtar gerekmez). Gezegenler + Ay + Güneş; az/alt penceresine göre
  cihazda filtrelenir.
- **Görüntü doğrulama** → Google **Gemini** (`gemini-2.5-flash-lite`), app'ten direkt
  multimodal çağrı. Anahtar **Bağlantı ekranında** girilir (cihazda saklanır).
- İlgili kod: `data/network/AstroApi.kt`, `data/network/GeminiApi.kt`,
  `data/repository/DirectBackendRepository.kt`.

## Çalıştırma

1. Android Studio'da projeyi aç → Gradle Sync.
2. Telefonu USB ile bağla veya emülatör başlat.
3. Run.
4. İlk açılışta konum izni ister; sensörler ve GPS otomatik başlar.

## Testler

```bash
gradlew test
```

Test dosyaları:

- `core/AstroMathTest.kt` — azimuth wrap-around mantığı.
- `data/Esp32RepositoryTest.kt` — MockWebServer ile status / target / move.
- `data/BackendRepositoryTest.kt` — observable-objects query parametreleri
  ve `verify-image` cevap akışları (success + correction + not-verified).

## Hata yönetimi

`AppErrorKind` enum'undan UI'a banner:

| Kind                    | Mesaj                              |
|-------------------------|------------------------------------|
| `ESP32_UNREACHABLE`     | ESP32 bağlı değil                  |
| `CAMERA_STREAM_FAILED`  | Kamera stream alınamıyor           |
| `GPS_UNAVAILABLE`       | GPS konumu yok                     |
| `COMPASS_UNCALIBRATED`  | Telefon pusulası kalibre değil     |
| `BACKEND_UNREACHABLE`   | Backend API cevap vermiyor         |
| `TARGET_NOT_VERIFIED`   | Hedef doğrulanamadı                |
| `PERMISSION_DENIED`     | Gerekli izinler verilmedi          |

---

## Uçtan Uca Akış & Örnek İstekler

### 1) ESP32 status polling

`TelescopeViewModel.startPolling()` 750ms'de bir:

```http
GET /status HTTP/1.1
Host: 192.168.4.1
```

Cevap:

```json
{
  "azimuth": 134.8, "altitude": 42.1,
  "targetAzimuth": 135.4, "targetAltitude": 42.8,
  "servoAz": 67.4, "servoAlt": 42.1,
  "gpsFix": true, "imuOk": true,
  "tracking": true, "targetLocked": false
}
```

### 2) Sky Area seçimi → backend obje listesi

```http
GET /observable-objects?latitude=41.0151&longitude=28.9795
    &datetime=2026-05-14T20:30:00Z
    &azimuthMin=120.0&azimuthMax=150.0
    &altitudeMin=30.0&altitudeMax=60.0 HTTP/1.1
Host: backend.example.com
```

Cevap:

```json
[
  { "name": "Mars", "type": "planet", "azimuth": 135.4, "altitude": 42.8,
    "magnitude": -1.2, "visible": true },
  { "name": "Arcturus", "type": "star", "azimuth": 128.0, "altitude": 51.2,
    "magnitude": 0.0, "visible": true }
]
```

### 3) Kullanıcı Mars'ı seçer → ESP32'ye target gönder

```http
POST /target HTTP/1.1
Host: 192.168.4.1
Content-Type: application/json

{ "name": "Mars", "azimuth": 135.4, "altitude": 42.8 }
```

### 4) Snapshot al → backend'e gönder (multipart)

```http
POST /verify-image HTTP/1.1
Host: backend.example.com
Content-Type: multipart/form-data; boundary=...

targetName=Mars
latitude=41.0151
longitude=28.9795
azimuth=134.8
altitude=42.1
image=<JPEG bytes>
```

Düzeltme gerekirse:

```json
{
  "verified": false, "targetName": "Mars",
  "azimuthCorrection": -0.8, "altitudeCorrection": 0.5,
  "message": "Move slightly left and up"
}
```

Doğrulandıysa:

```json
{
  "verified": true, "targetName": "Mars",
  "azimuthCorrection": 0, "altitudeCorrection": 0,
  "message": "Target verified"
}
```

### 5) App otomatik düzeltme gönderir

```http
POST /correction HTTP/1.1
Host: 192.168.4.1
Content-Type: application/json

{ "azimuthCorrection": -0.8, "altitudeCorrection": 0.5 }
```

### 6) Manuel hareket (kullanıcı "sağ + orta")

```http
POST /move HTTP/1.1
Host: 192.168.4.1
Content-Type: application/json

{ "direction": "right", "step": "medium" }
```

`direction`: `left | right | up | down`
`step`: `small | medium | large`

### 7) Kalibrasyon

```http
POST /calibrate HTTP/1.1
Host: 192.168.4.1
```

### 8) MJPEG canlı stream

`MjpegView`, ESP32'nin `multipart/x-mixed-replace` cevabını okur ve her
JPEG frame'ini ImageView'e bağlar:

```http
GET /stream HTTP/1.1
Host: 192.168.4.1
```

---

## Notlar

- `AndroidManifest.xml` `usesCleartextTraffic="true"` + ESP32 IP'leri
  `res/xml/network_security_config.xml`'de whitelist'tir.
- `OtoskopApp.sensorRepo` Activity yaşam döngüsünde start/stop edilir.
- `TelescopeViewModel`'in polling job'ı Fragment `onStart/onStop`'ta
  start/stop edilir; pil tüketimini kontrol altında tutar.
- ESP32 IP'si runtime'da değişebilir: `EndpointRewriteInterceptor` Retrofit
  base URL'i yerine her isteği rewrite eder.
