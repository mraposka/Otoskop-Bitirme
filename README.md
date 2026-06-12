# Otoskop — Motorlu Akıllı Teleskop / Gökyüzü İşaretleyici

Otoskop; bir gök cismini (gezegen, yıldız, takımyıldız vb.) seçtiğinde, kendi
konumunu ve yönelimini ölçüp teleskopu **otomatik olarak** o hedefin azimut/
yükseklik (altitude) açısına döndüren, üzerindeki kamera ile canlı görüntü
veren bir bitirme projesidir.

Sistem üç parçadan oluşur:

1. **Arduino Mega 2560** — "kas + duyu": IMU/pusula, GPS ve iki servo motoru
   sürer. Hedefe yumuşak şekilde nişan alır.
2. **ESP32-CAM (AI-Thinker)** — "ağ geçidi + göz": Wi-Fi, HTTP API, kamera
   (anlık kare + canlı MJPEG yayını) ve Mega ile köprü görevi görür.
3. **Android uygulaması** — kullanıcı arayüzü: hedef seçimi, manuel oynatma,
   kalibrasyon, canlı kamera, telemetri gösterimi.

```
   ┌──────────────┐   Wi-Fi/HTTP   ┌──────────────┐   UART (JSON)  ┌──────────────┐
   │ Android App  │ ─────────────► │  ESP32-CAM   │ ─────────────► │ Arduino Mega │
   │ (hedef/UI)   │ ◄───────────── │ (ağ + kamera)│ ◄───────────── │ (sensör+motor)│
   └──────────────┘   status/JPEG  └──────────────┘   telemetri    └──────────────┘
                                                                         │
                                                            ┌────────────┼────────────┐
                                                         MPU9250        GPS        2x Servo
                                                       (IMU+pusula)  (NEO-6M)    (azimut/alt)
```

---

## İçindekiler

- [Depo yapısı](#depo-yapısı)
- [Nasıl çalışır?](#nasıl-çalışır)
- [Donanım ve kablolama](#donanım-ve-kablolama)
- [Yazılım kurulumu (Arduino IDE)](#yazılım-kurulumu-arduino-ide)
- [Mega firmware yükleme](#mega-firmware-yükleme)
- [ESP32-CAM firmware yükleme](#esp32-cam-firmware-yükleme)
- [Wi-Fi ve cihaz bulma](#wi-fi-ve-cihaz-bulma)
- [HTTP API](#http-api)
- [UART köprüsü ve JSON protokolü](#uart-köprüsü-ve-json-protokolü)
- [Kablosuz kod yükleme (OTA)](#kablosuz-kod-yükleme-ota)
- [Sorun giderme](#sorun-giderme)

---

## Depo yapısı

```
Otoskop/
├── ESP32_Arduino_Firmware/
│   └── mega_otoskop/
│       └── mega_otoskop.ino        # Arduino Mega 2560 firmware (sensör + motor)
├── ESP32_Pure_Firmware/
│   ├── esp32cam_otoskop/
│   │   └── esp32cam_otoskop.ino    # ESP32-CAM firmware (Wi-Fi + kamera + köprü)
│   └── ota/
│       ├── firmware.bin            # Bulut OTA için yayınlanan derlenmiş binary
│       └── version.json            # OTA sürüm bilgisi {"version", "url"}
├── ota_release/
│   └── release.ps1                 # OTA yayın yardımcısı (PowerShell)
├── ServoTest.ino                   # Servo açı testi için bağımsız yardımcı sketch
└── README.md
```

> Not: `firmware.bin` bilerek repoda tutulur (`.gitignore` onu istisna tutar),
> çünkü cihazlar OTA güncellemesini doğrudan GitHub raw URL'sinden indirir.

---

## Nasıl çalışır?

1. **Konum & yönelim ölçümü (Mega):**
   - GPS'ten enlem/boylam (lat/lon) alınır.
   - MPU9250 ile **azimut** (tilt-kompanze manyetometre + gyro complementary
     filter ile gerçek pusula yönü) ve **yükseklik açısı** (ivmeölçer pitch)
     hesaplanır.
2. **Hedef belirleme (App → ESP → Mega):**
   - Uygulama bir hedefin azimut/altitude değerini gönderir.
   - Mega, hedefi servo açısına haritalar ve motorları yumuşakça (slew) döndürür.
   - Hedefe yeterince yaklaşınca **kilit (lock)** durumu raporlanır.
3. **Geri bildirim (Mega → ESP → App):**
   - Mega ~10 Hz telemetri (anlık açı, hedef açı, servo açıları, GPS, IMU/track/
     lock durumları) yollar.
   - ESP bunu önbelleğe alır ve `/status` ile uygulamaya servis eder.
4. **Görüntü (ESP):**
   - Kamera tek kare (`/camera`) veya canlı MJPEG yayını (`/stream`, ayrı port)
     olarak verilir.

---

## Donanım ve kablolama

### Bileşenler

| Parça | Açıklama |
|-------|----------|
| Arduino Mega 2560 | Ana kontrolcü (sensör + motor) |
| ESP32-CAM (AI-Thinker) | Wi-Fi + kamera + köprü |
| MPU9250 | 9 eksenli IMU (ivme + gyro + AK8963 manyetometre) |
| GPS modülü | NEO-6M / M8N (TinyGPSPlus uyumlu) |
| 2x Servo | Azimut (pan) + Altitude (tilt) ekseni |
| USB–TTL adaptör | ESP32-CAM ilk programlaması için (kartta USB yoksa) |
| Gerilim bölücü | Mega 5V TX → ESP 3.3V RX hattı için |

### Mega bağlantıları

| Bağlantı | Pinler |
|----------|--------|
| MPU9250 | VCC 3.3V, GND, SDA → 20, SCL → 21 |
| GPS | VCC 5V, GND, GPS-TX → Mega RX2 (17), GPS-RX → Mega TX2 (16) |
| Servo Azimut | Sinyal → pin **9** (harici 5V besleme, ortak GND) |
| Servo Altitude | Sinyal → pin **10** |
| ESP link (TX) | Mega TX1 (18) → **gerilim bölücü 5V→3.3V** → ESP RX2 (GPIO13) |
| ESP link (RX) | Mega RX1 (19) ← ESP TX2 (GPIO15) (3.3V doğrudan) |

### ESP32-CAM bağlantıları

| Bağlantı | Pin |
|----------|-----|
| Mega'dan veri girişi (RX2) | GPIO13 (Mega TX1 → gerilim bölücü üzerinden) |
| Mega'ya veri çıkışı (TX2) | GPIO15 (→ Mega RX1) |
| Flash LED (OTA göstergesi) | GPIO4 |

> ⚠️ **Kritik:** Her iki kartın **GND'si ortak** olmalıdır. Mega'nın 5V TX'i
> doğrudan ESP'ye bağlanmamalı; **gerilim bölücü** ile 3.3V'a düşürülmelidir.
> ESP'yi programlarken Mega bağlantısını sökün (UART karışmasın).

---

## Yazılım kurulumu (Arduino IDE)

### 1) ESP32 kart desteği

1. Arduino IDE → **File → Preferences → Additional boards manager URLs**
2. Şu adresi ekleyin:
   `https://espressif.github.io/arduino-esp32/package_esp32_index.json`
3. **Tools → Board → Boards Manager** → **esp32** (Espressif) yükleyin.

### 2) Kütüphaneler

**Sketch → Include Library → Manage Libraries** üzerinden kurun:

| Kütüphane | Yazar / Sürüm | Kullanan |
|-----------|---------------|----------|
| **ArduinoJson** | Benoît Blanchon (6.x/7.x) | Mega + ESP |
| **TinyGPSPlus** | Mikal Hart | Mega |
| **Servo** | Arduino (built-in) | Mega |
| **ESP32Servo** | (alternatif, gerekirse) | — |

`WiFi`, `WebServer`, `Wire`, `esp_camera`, `ArduinoOTA`, `HTTPClient`,
`HTTPUpdate`, `ESPmDNS` ESP32 çekirdeğiyle birlikte gelir.

---

## Mega firmware yükleme

1. **File → Open** ile `ESP32_Arduino_Firmware/mega_otoskop/mega_otoskop.ino`
   dosyasını açın.
2. **Tools → Board → Arduino Mega or Mega 2560** seçin, doğru COM portunu seçin.
3. **Upload**.
4. Serial Monitor'ü **115200** baud'da açın.

`DEBUG_USB` sabiti `1` iken Mega, hem komutları (`[CMD] ...`), hem 10 Hz
telemetriyi (`{"az":...}`), hem de bir "kalp atışı" satırını (`[LOOP] n=... imuOk=...`)
USB seri monitöre yazar. Bu, sensör/bağlantı doğrulaması için çok kullanışlıdır.

### Mega'da ayarlanabilir parametreler

| Sabit | Açıklama |
|-------|----------|
| `AZ_RANGE_MIN_DEG` / `AZ_RANGE_MAX_DEG` | Azimut servosunun kapsadığı açı aralığı (varsayılan 0–180°) |
| `ALT_RANGE_MIN_DEG` / `ALT_RANGE_MAX_DEG` | Altitude servo aralığı (0–90°) |
| `SERVO_SLEW_DEG_PER_LOOP` | Servo yumuşatma hızı |
| `LOCK_TOLERANCE_DEG` | Hedef kilidi toleransı |
| `MAG_DECLINATION_DEG` | Yerel manyetik sapma (İstanbul ≈ +6°) |
| `magOffX/Y/Z` | Manyetometre hard-iron offset (kalibrasyonla bulunur) |

---

## ESP32-CAM firmware yükleme

1. **File → Open** ile
   `ESP32_Pure_Firmware/esp32cam_otoskop/esp32cam_otoskop.ino` açın.
2. **Tools** ayarları:

| Ayar | Değer |
|------|-------|
| Board | **AI Thinker ESP32-CAM** |
| Upload Speed | 115200 veya 921600 |
| Flash Frequency | 80MHz |
| Partition Scheme | **Minimal SPIFFS (1.9MB APP with OTA)** (OTA için şart) |
| PSRAM | **Enabled** |

3. Kartta USB yoksa **USB–TTL** adaptör kullanın (GPIO0 ↔ GND ile boot moduna alıp
   reset). Doğru COM portunu seçip **Upload** edin.
4. Serial Monitor: **115200** baud.

İlk yükleme bir kez kabloyla yapılmalıdır; sonrasında OTA ile kablosuz
güncellenebilir.

---

## Wi-Fi ve cihaz bulma

ESP, **AP + STA** modunda aynı anda çalışır:

- **AP (erişim noktası):** SSID `Otoskop`, şifre `otoskop123`, IP genelde
  **192.168.4.1**. Android uygulaması doğrudan buna bağlanabilir.
- **STA (istemci):** `WIFI_NETS` listesindeki ağlardan (telefon hotspot'u / ev
  Wi-Fi'si) hangisi varsa ona bağlanır (`WiFiMulti`). Bu, internet erişimi ve
  bulut OTA için gerekir.
- **mDNS:** Cihaz kendini `otoskop.local` olarak yayınlar. Uygulama IP yerine bu
  adı kullanarak cihazı DHCP IP değişse bile otomatik bulabilir.

Ayarlar `esp32cam_otoskop.ino` dosyasında:

```cpp
const char* AP_SSID = "Otoskop";
const char* AP_PASS = "otoskop123";
WifiCred WIFI_NETS[] = {
  { "Nothing", "123456789a" },   // kendi ağ(lar)ını ekle
};
```

---

## HTTP API

API port **80**'de, MJPEG yayını ise tıkanmayı önlemek için ayrı bir port
**81**'de servis edilir.

| Metot | Endpoint | Açıklama |
|-------|----------|----------|
| GET | `/status` | Telemetri JSON (uygulama bunu sürekli sorgular) |
| GET | `/camera` | Tek JPEG kare (snapshot) |
| GET | `/stream` | Canlı MJPEG yayını (**port 81**) |
| GET | `/gps` | `{"lat":.., "lon":.., "fix":bool}` |
| POST | `/target` | `{"name":.., "azimuth":.., "altitude":..}` |
| POST | `/move` | `{"direction":"left\|right\|up\|down", "step":"small\|medium\|large"}` |
| POST | `/correction` | `{"azimuthCorrection":.., "altitudeCorrection":..}` |
| POST | `/calibrate` | Gyro + manyetometre kalibrasyonunu tetikler |
| GET | `/ota/check` | Bulut OTA'yı hemen kontrol eder (yeni sürüm varsa günceller) |

Tüm yanıtlar CORS (`Access-Control-Allow-Origin: *`) başlığı taşır.

### `/status` yanıt alanları

```json
{
  "azimuth": 134.8, "altitude": 42.1,
  "targetAzimuth": 135.4, "targetAltitude": 42.8,
  "servoAz": 67.4, "servoAlt": 42.1,
  "gpsFix": true, "imuOk": true,
  "tracking": true, "targetLocked": false,
  "megaRaw": "...", "megaAgeMs": 80, "megaLines": 1234, "megaBytes": 56789
}
```

`megaRaw / megaAgeMs / megaLines / megaBytes` alanları Mega↔ESP UART bağlantısının
teşhisi içindir (uygulamadaki konsolda görünür). `megaBytes = 0` → fiziksel hat
yok (GND/gerilim bölücü/kablo); `megaAgeMs = -1` → Mega'dan hiç veri gelmedi.

---

## UART köprüsü ve JSON protokolü

ESP ile Mega, 115200 8N1 baud'da satır-sonlu (`\n`) JSON ile konuşur.

**ESP → Mega (komutlar):**

```json
{"cmd":"target","az":135.4,"alt":42.8}
{"cmd":"move","dir":"right","step":"medium"}
{"cmd":"correction","daz":-0.8,"dalt":0.5}
{"cmd":"track","on":true}
{"cmd":"calibrate"}
```

**Mega → ESP (telemetri, ~10 Hz):**

```json
{"az":134.8,"alt":42.1,"taz":135.4,"talt":42.8,"sAz":67.4,"sAlt":42.1,
 "roll":1.2,"pitch":42.1,"yaw":134.8,
 "gps":true,"lat":41.0,"lon":28.9,"imu":true,"trk":true,"lock":false}
```

---

## Kablosuz kod yükleme (OTA)

ESP firmware'i ilk kabloyla yüklendikten sonra **iki yolla** kablosuz
güncellenebilir:

### 1) Aynı ağda — ArduinoOTA

Bilgisayar ESP ile aynı ağdayken Arduino IDE → **Tools → Port** içinde
`otoskop at <IP>` belirir. Normal şekilde **Upload** yapılır.
OTA şifresi: `otoskop123`.

### 2) İnternet üzerinden — Bulut OTA (HTTP pull)

Masaüstü ile ESP farklı ağlardayken kullanılır. ESP belirli aralıklarla
(varsayılan **5 dk**) `version.json` dosyasını kontrol eder:

```json
{ "version": 12, "url": "https://.../firmware.bin" }
```

`version > FW_VERSION` ise `firmware.bin` indirilip cihaz kendini günceller
(indirme sırasında flash LED yanıp söner). `GET /ota/check` ile güncelleme
beklemeden tetiklenebilir.

### Yeni sürüm yayınlama

1. `esp32cam_otoskop.ino` içindeki `#define FW_VERSION` değerini artırın.
2. Arduino IDE → **Sketch → Export Compiled Binary**.
3. Proje kökünde PowerShell ile yardımcı betiği çalıştırın:

```powershell
./ota_release/release.ps1
```

Betik, derlenen `.bin`'i `ESP32_Pure_Firmware/ota/firmware.bin`'e kopyalar ve
`version.json`'daki sürümü `.ino`'daki `FW_VERSION` ile eşitler (BOM'suz UTF-8).
Ardından bu iki dosyayı GitHub'a yükleyin. ESP en geç 5 dk içinde kendini
günceller.

> ⚠️ Betik güvenlik kontrolü yapar: `.ino` dosyası `.bin`'den yeniyse uyarır —
> aksi halde cihaz sonsuz OTA döngüsüne girebilir. `FW_VERSION`'ı değiştirdikten
> sonra **mutlaka yeniden derleyin**.

---

## Sorun giderme

| Belirti | Olası neden / çözüm |
|---------|---------------------|
| Uygulamada "ESP32 bağlantısı sağlanamadı" | Telefon `Otoskop` AP'sine bağlı mı? IP `192.168.4.1` mi? mDNS için `otoskop.local` deneyin |
| `megaBytes = 0` | UART fiziksel hattı yok: ortak GND yok, gerilim bölücü yanlış, Mega TX1 (pin 18) / ESP RX2 (GPIO13) bağlantısı hatalı |
| `megaBytes` artıyor ama `megaLines = 0` | Sinyal var ama bozuk — her iki taraf da 115200 8N1 olmalı |
| `imuOk = false` (sürekli kırmızı) | MPU9250 I2C bağlantısı; kod 100 kHz'de çalışır ve saniyede bir yeniden init dener. Kabloları/SDA-SCL'yi kontrol edin |
| Kamera init hatası (`0x105`) | Besleme yetersiz/brownout; kod brownout dedektörünü kapatır, PWDN toggle + 20/10 MHz XCLK dener. Yeterli akım veren USB/güç kullanın |
| Pusula sapması | `calibrate` çalıştırın; `MAG_DECLINATION_DEG`'i kendi konumunuza göre ayarlayın |
| Stream açıkken API donuyor | Yeni firmware'de yayın ayrı portta (81); stream döngüsü içinde API pompalanır. Eski firmware'i güncelleyin |
| Bulut OTA sonsuz döngü | `FW_VERSION` artırıldı ama yeniden derlenmedi; `release.ps1` bu durumda uyarır |
| OTA: **Could Not Activate The Firmware** | İndirme tamam ama doğrulama/aktivasyon başarısız. **En sık neden:** cihaz veya `firmware.bin` **farklı Partition Scheme** ile derlenmiş (ör. cihazda *Huge APP No OTA*, OTA bin *Minimal SPIFFS*). **Çözüm:** Arduino IDE → AI Thinker ESP32-CAM → **Minimal SPIFFS (1.9MB APP with OTA)** + PSRAM Enabled ile **USB'den bir kez** yükle; GitHub'a koyacağın `.bin`'i de **aynı ayarlarla** Export Compiled Binary ile üret |

---

## Lisans / Notlar

Bu, eğitim amaçlı bir bitirme projesidir. Wi-Fi şifreleri, OTA şifresi ve
TLS ayarları (sertifika doğrulaması kapalı) hobi kullanımı içindir; üretim
ortamı için sertleştirilmelidir.

Depo: [github.com/mraposka/Otoskop](https://github.com/mraposka/Otoskop)
