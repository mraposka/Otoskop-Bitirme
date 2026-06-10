# Otoskop — Arduino IDE ile yükleme

Sketch klasörü: **`OtoskopTelescope/`** (içinde `OtoskopTelescope.ino` + `board_config.h`).

## 1) ESP32 kart desteği

1. Arduino IDE → **File → Preferences → Additional boards manager URLs**
2. Şu adresi ekle (zaten varsa virgülle ayır):

   `https://espressif.github.io/arduino-esp32/package_esp32_index.json`

3. **Tools → Board → Boards Manager** → **esp32** arat → **esp32** (Espressif) yükle.

## 2) Kütüphaneler

**Sketch → Include Library → Manage Libraries** veya Library Manager’dan kur:

| Kütüphane | Arama |
|-----------|--------|
| **ArduinoJson** | Benoît Blanchon, sürüm 7.x |
| **TinyGPSPlus** | Mikal Hart |
| **ESP32Servo** | Kevin Harrington / Madhephaestus |

WiFi, WebServer, Wire, esp_camera ESP32 çekirdeğiyle gelir.

## 3) Kart ayarları (ESP32-CAM AI-Thinker)

**File → Open** ile `OtoskopTelescope/OtoskopTelescope.ino` dosyasını aç (klasör adı ile .ino adı aynı olmalı).

**Tools** menüsü örnek ayarlar:

| Ayar | Değer |
|------|--------|
| Board | **AI Thinker ESP32-CAM** |
| Upload Speed | 115200 veya 921600 |
| Flash frequency | 80MHz |
| Partition Scheme | **Huge APP (3MB No OTA/1MB SPIFFS)** veya kamera için önerilen şema |
| PSRAM | **Enabled** |

Kartında USB yoksa **USB–TTL** ile programlama (GPIO0 ↔ GND ile boot modu vb.) gerekir.

## 4) Yükleme

1. Doğru **COM port** seç (Tools → Port).
2. **Sketch → Upload**.

Serial Monitor: **115200** baud.

## 5) Wi-Fi

Varsayılan AP: `Otoskop-ESP` / `telescope123` — IP genelde **192.168.4.1**.

SSID/şifreyi `OtoskopTelescope.ino` dosyasındaki `WIFI_AP_SSID` / `WIFI_AP_PASSWORD` sabitlerinden değiştir.

## PlatformIO ile aynı kod

Arduino sürümü `esp32_firmware/src/main.cpp` ile aynı mantıkta tutulmalı; güncelleme yaparken iki yeri senkronize et veya tek kaynak olarak birini seç.
