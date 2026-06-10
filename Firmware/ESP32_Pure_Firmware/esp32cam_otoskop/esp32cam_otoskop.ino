/*
 * Otoskop - ESP32-CAM (AI-Thinker) firmware
 * --------------------------------------------------------------------------
 * Gorev: WiFi kapisi + kamera + Mega koprusu.
 *   - WiFi AP (SSID "Otoskop", IP 192.168.4.1) -> Android app baglanir.
 *   - HTTP API: app'in bekledigi tum endpoint'ler.
 *   - Kamera: /camera (tek JPEG), /stream (MJPEG canli).
 *   - Mega ile UART2 (Serial2) uzerinden satir-sonlu JSON konusur:
 *       komutlari Mega'ya iletir, telemetriyi Mega'dan okuyup /status verir.
 *
 * HTTP API (port 80):
 *   GET  /status      -> TelescopeStatus JSON (app polling)
 *   GET  /camera      -> image/jpeg (tek kare snapshot)
 *   GET  /stream      -> multipart/x-mixed-replace MJPEG
 *   GET  /gps         -> {"lat":..,"lon":..,"fix":bool}
 *   POST /target      -> {"name":..,"azimuth":..,"altitude":..}
 *   POST /move        -> {"direction":"left|right|up|down","step":"small|medium|large"}
 *   POST /correction  -> {"azimuthCorrection":..,"altitudeCorrection":..}
 *   POST /calibrate
 *   GET  /ota/check   -> bulut OTA'yi hemen tetikler (yeni surum varsa gunceller)
 *
 * Mega link kablolama:
 *   ESP RX2 = GPIO13  <- Mega TX1(18) [GERILIM BOLUCU 5V->3.3V uzerinden]
 *   ESP TX2 = GPIO15  -> Mega RX1(19)
 *   ORTAK GND. NOT: Kart programlanirken Mega baglantisini sok (UART karismasin).
 *
 * Gerekli: Arduino-ESP32 board paketi (esp32 by Espressif), ArduinoJson.
 * Kart secimi: "AI Thinker ESP32-CAM". PSRAM acik.
 *
 * AG / BULMA:
 *   - ESP, WIFI_NETS'teki aglardan (telefon hotspot + ev wifi) hangisi varsa
 *     ona baglanir (WiFiMulti). Ayni anda "Otoskop" AP'si de aciktir.
 *   - mDNS ile kendini "otoskop.local" olarak yayinlar; Android app IP yerine
 *     bunu kullanip cihazi otomatik bulur (DHCP IP degisse de calisir).
 *
 * KABLOSUZ KOD YUKLEME - IKI YOL:
 *
 * 1) AYNI AG (ArduinoOTA): bilgisayar ESP ile ayni agdayken Arduino IDE
 *    Tools > Port'ta "otoskop at <IP>" cikar, normal Upload.
 *
 * 2) INTERNET UZERINDEN / BULUT (HTTP OTA pull) -- masaustu ile ESP farkli
 *    aglardayken (orn. ESP telefon hotspot'unda, sen kablolu internette):
 *      - Belli araliklarla OTA_VERSION_URL'deki version.json'a bakar:
 *          {"version": 2, "url": "https://.../firmware.bin"}
 *      - version > FW_VERSION ise firmware.bin'i indirip kendini gunceller.
 *      - Sen masaustunde .bin'i derleyip GitHub'a push edersin (ota/ klasoru +
 *        version.json'daki sayiyi artir). Yardimci: tools/release.ps1
 *      - GET /ota/check  -> guncellemeyi hemen tetikler (beklemeden).
 *
 * NOT: Her iki yol da OTA'li partition semasi ister
 *   (Tools > Partition Scheme > "Minimal SPIFFS (1.9MB APP with OTA)").
 *   Ilk yukleme bir kez kabloyla yapilmalidir; sonrasi kablosuz.
 */

#include "esp_camera.h"
#include <WiFi.h>
#include <WiFiMulti.h>          // birden fazla agdan birine otomatik baglanma
#include <ESPmDNS.h>            // otoskop.local -> app otomatik bulsun
#include <WebServer.h>
#include <ArduinoJson.h>
#include <ArduinoOTA.h>
#include <HTTPClient.h>
#include <WiFiClientSecure.h>
#include <HTTPUpdate.h>
#include "soc/soc.h"             // brownout dedektorunu kapatmak icin
#include "soc/rtc_cntl_reg.h"
#include "lwip/sockets.h"        // SO_SNDTIMEO: takilan stream yazimini bosa cikar

// ----------------------- AI-Thinker ESP32-CAM pinleri ----------------------
#define PWDN_GPIO_NUM     32
#define RESET_GPIO_NUM    -1
#define XCLK_GPIO_NUM      0
#define SIOD_GPIO_NUM     26
#define SIOC_GPIO_NUM     27
#define Y9_GPIO_NUM       35
#define Y8_GPIO_NUM       34
#define Y7_GPIO_NUM       39
#define Y6_GPIO_NUM       36
#define Y5_GPIO_NUM       21
#define Y4_GPIO_NUM       19
#define Y3_GPIO_NUM       18
#define Y2_GPIO_NUM        5
#define VSYNC_GPIO_NUM    25
#define HREF_GPIO_NUM     23
#define PCLK_GPIO_NUM     22

// Mega link UART2 pinleri
#define MEGA_RX_PIN 13   // ESP girisi  (Mega TX'ten)
#define MEGA_TX_PIN 15   // ESP cikisi  (Mega RX'e)

// AI-Thinker ESP32-CAM uzerindeki parlak beyaz flash LED. OTA indirilirken
// yanip soner (gorsel gosterge), indirme bitince soner.
#define FLASH_LED_PIN 4

// ---- UART2 LOOPBACK SELF-TEST -------------------------------------------
// "FIZIKSEL HAT YOK (0 byte)" gorduysen once ESP'nin kendi UART'inin saglam
// olup olmadigini KANITLA:
//   1) Mega'yi cikar (kablolari sok).
//   2) ESP GPIO15 (TX2) -> GPIO13 (RX2) arasina kisa bir jumper tak (DOGRUDAN,
//      gerilim bolucu YOK; ikisi de 3.3V).
//   3) Asagidaki 1 yapip yukle.
// Sonuc:
//   - byte/lines ARTIYORSA  -> ESP UART2 + GPIO13/15 SAGLAM. Sorun %100 Mega
//     kablolamasinda: ORTAK GND yok / gerilim bolucu / Mega TX1=pin18 degil.
//   - hala 0 byte           -> ESP pini/UART sorunlu; MEGA_RX_PIN'i degistir
//     (orn. 14) ya da baska bir ESP32 kullan.
// Test bitince tekrar 0 yapip Mega'yi geri bagla.
#define MEGA_SELFTEST 0

const char* AP_SSID = "Otoskop";
const char* AP_PASS = "otoskop123";   // en az 8 karakter

// mDNS adi: app cihazi "otoskop.local" olarak bulur (IP degisse de). Ayni ad
// ArduinoOTA hostname'i olarak da kullanilir.
const char* MDNS_HOST = "otoskop";
const char* OTA_PASS  = "otoskop123";   // kablosuz yukleme sifresi

// ----- Internet baglantisi (STA) -> hem internet ihtiyaci hem bulut OTA -----
// Birden fazla ag tanimla; ESP hangisi varsa ona baglanir (WiFiMulti).
// Yeni ag eklemek icin satir ekle, gerekmeyeni sil.
struct WifiCred { const char* ssid; const char* pass; };
WifiCred WIFI_NETS[] = {
  { "Nothing", "123456789a" },
};
WiFiMulti wifiMulti;

// ----- Bulut OTA (HTTP pull) ------------------------------------------------
// Her yeni firmware'de bu sayiyi VE version.json icindeki "version"i artir.
#define FW_VERSION 11

// version.json'in ham (raw) adresi. Ornek GitHub raw:
//   https://raw.githubusercontent.com/KULLANICI/REPO/main/ota/version.json
const char* OTA_VERSION_URL = "https://raw.githubusercontent.com/mraposka/Otoskop/refs/heads/main/ESP32_Pure_Firmware/ota/version.json";

// Bulut OTA kontrol araligi (ms). 0 = otomatik kontrol kapali (sadece /ota/check).
const unsigned long OTA_CHECK_INTERVAL = 5UL * 60UL * 1000UL;  // 5 dk
unsigned long lastOtaCheck = 0;

WebServer server(80);          // API (status/komut/snapshot) - hizli cevap verir
// MJPEG stream'i AYRI bir port'ta (81) servis ediyoruz. Tek-is parcacikli
// WebServer, /stream'in sonsuz dongusunde TIKANIR; ayni port'taki /status ve
// komutlar cevapsiz kalir ("ESP32 baglantisi saglanmadi"). Stream'i ayri
// server'a alip, stream dongusunun icinde API server'ini de pompalayarak
// (server.handleClient) yayim sirasinda da API'yi canli tutuyoruz.
WebServer streamServer(81);    // sadece /stream (MJPEG)

// Mega'dan gelen son telemetri (cache)
struct Telemetry {
  float az = 0, alt = 0, taz = 0, talt = 0, sAz = 90, sAlt = 45;
  float lat = 0, lon = 0;
  bool gps = false, imu = false, trk = false, lock = false;
  unsigned long lastUpdate = 0;
} tele;

char megaBuf[256];
uint16_t megaLen = 0;

// --- Mega link debug (USB serial + /status) ---
char lastMegaLine[256] = "";        // Mega'dan gelen son ham satir
unsigned long lastMegaRecvMs = 0;   // son satirin alindigi an (0 = hic gelmedi)
unsigned long megaLineCount = 0;    // toplam alinan tam satir (\n'li) sayisi
unsigned long megaByteCount = 0;    // Serial2'den okunan TOPLAM ham byte
unsigned long lastMegaByteMs = 0;   // son byte'in geldigi an (0 = hic byte yok)

// --------------------------- Kamera ----------------------------------------
// PWDN pinini elle dusuk-yuksek-dusuk yaparak sensoru fiziksel reset eder.
// Bazi AI-Thinker kartlarinda guc-acilis zamanlamasi yuzunden sensor ilk
// init'te cevap vermez (0x105); bu toggle cogu zaman duzeltir.
static void cameraPowerCycle() {
  if (PWDN_GPIO_NUM < 0) return;
  pinMode(PWDN_GPIO_NUM, OUTPUT);
  digitalWrite(PWDN_GPIO_NUM, HIGH);   // power-down
  delay(50);
  digitalWrite(PWDN_GPIO_NUM, LOW);    // power-up
  delay(50);
}

bool initCamera() {
  camera_config_t config;
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = Y2_GPIO_NUM;
  config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;
  config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;
  config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;
  config.pin_d7 = Y9_GPIO_NUM;
  config.pin_xclk = XCLK_GPIO_NUM;
  config.pin_pclk = PCLK_GPIO_NUM;
  config.pin_vsync = VSYNC_GPIO_NUM;
  config.pin_href = HREF_GPIO_NUM;
  config.pin_sccb_sda = SIOD_GPIO_NUM;
  config.pin_sccb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;
  config.pixel_format = PIXFORMAT_JPEG;
  config.grab_mode = CAMERA_GRAB_LATEST;
  config.jpeg_quality = 12;
  config.frame_size = FRAMESIZE_VGA;       // 640x480 (demo icin yeterli)
  if (psramFound()) {
    config.fb_location = CAMERA_FB_IN_PSRAM;
    config.fb_count = 2;
  } else {
    config.fb_location = CAMERA_FB_IN_DRAM;
    config.frame_size = FRAMESIZE_QVGA;
    config.fb_count = 1;
  }

  // XCLK'i once 20MHz, olmazsa 10MHz dene (dusuk hiz marjinal kart/beslemede
  // daha guvenilir). Her hizi birkac kez, arada PWDN reset ile tekrarla.
  const int xclks[] = { 20000000, 10000000 };
  for (int x = 0; x < 2; x++) {
    config.xclk_freq_hz = xclks[x];
    for (int attempt = 1; attempt <= 3; attempt++) {
      cameraPowerCycle();
      esp_err_t err = esp_camera_init(&config);
      if (err == ESP_OK) {
        Serial.printf("Kamera OK (xclk=%d MHz, deneme %d)\n", xclks[x] / 1000000, attempt);
        return true;
      }
      Serial.printf("Kamera init 0x%x (xclk=%d MHz, deneme %d/3)\n",
                    err, xclks[x] / 1000000, attempt);
      esp_camera_deinit();
      delay(250);
    }
  }
  return false;
}

// --------------------------- Mega koprusu ----------------------------------
void sendToMega(const JsonDocument& doc) {
  serializeJson(doc, Serial2);
  Serial2.print('\n');
}

void parseMegaLine(const char* line) {
  // 512: Mega telemetrisi roll/pitch/yaw eklenince buyudu; 256 tasip parse'i
  // bozardi (telemetri hic guncellenmezdi). Genis tutuyoruz.
  StaticJsonDocument<512> doc;
  if (deserializeJson(doc, line)) return;
  if (!doc.containsKey("az")) return;
  tele.az = doc["az"] | tele.az;
  tele.alt = doc["alt"] | tele.alt;
  tele.taz = doc["taz"] | tele.taz;
  tele.talt = doc["talt"] | tele.talt;
  tele.sAz = doc["sAz"] | tele.sAz;
  tele.sAlt = doc["sAlt"] | tele.sAlt;
  tele.gps = doc["gps"] | false;
  tele.lat = doc["lat"] | tele.lat;
  tele.lon = doc["lon"] | tele.lon;
  tele.imu = doc["imu"] | false;
  tele.trk = doc["trk"] | false;
  tele.lock = doc["lock"] | false;
  tele.lastUpdate = millis();
}

void pumpMega() {
  while (Serial2.available()) {
    char c = Serial2.read();
    megaByteCount++;            // ham byte sayaci (parse'tan bagimsiz)
    lastMegaByteMs = millis();
    if (c == '\n' || c == '\r') {
      if (megaLen > 0) {
        megaBuf[megaLen] = '\0';
        // DEBUG: ham satiri sakla + USB/FTDI serial'e bas. Boylece hem ESP
        // seri monitorunden hem de uygulamadaki konsoldan Mega verisi gorunur.
        strncpy(lastMegaLine, megaBuf, sizeof(lastMegaLine) - 1);
        lastMegaLine[sizeof(lastMegaLine) - 1] = '\0';
        lastMegaRecvMs = millis();
        megaLineCount++;
        Serial.print(F("[MEGA] "));
        Serial.println(megaBuf);
        parseMegaLine(megaBuf);
        megaLen = 0;
      }
    } else if (megaLen < sizeof(megaBuf) - 1) {
      megaBuf[megaLen++] = c;
    } else {
      megaLen = 0;
    }
  }
}

// --------------------------- HTTP handler'lar ------------------------------
void sendCors() {
  server.sendHeader("Access-Control-Allow-Origin", "*");
}

void handleStatus() {
  StaticJsonDocument<512> doc;
  doc["azimuth"] = tele.az;
  doc["altitude"] = tele.alt;
  doc["targetAzimuth"] = tele.taz;
  doc["targetAltitude"] = tele.talt;
  doc["servoAz"] = tele.sAz;
  doc["servoAlt"] = tele.sAlt;
  doc["gpsFix"] = tele.gps;
  doc["imuOk"] = tele.imu && (millis() - tele.lastUpdate < 2000);
  doc["tracking"] = tele.trk;
  doc["targetLocked"] = tele.lock;
  // Mega link debug alanlari (uygulamadaki konsol bunlari gosterir):
  //  megaAgeMs = -1 -> Mega'dan HIC veri gelmedi (UART hatti/voltaj bolucu/GND)
  doc["megaRaw"] = (const char*)lastMegaLine;   // zero-copy (global, hemen serialize)
  doc["megaAgeMs"] = (lastMegaRecvMs == 0) ? -1L : (long)(millis() - lastMegaRecvMs);
  doc["megaLines"] = megaLineCount;
  doc["megaBytes"] = megaByteCount;             // 0 ise FIZIKSEL: kablo/GND/divider
  doc["megaByteAgeMs"] = (lastMegaByteMs == 0) ? -1L : (long)(millis() - lastMegaByteMs);
  String out;
  serializeJson(doc, out);
  sendCors();
  server.send(200, "application/json", out);
}

void handleGps() {
  StaticJsonDocument<128> doc;
  doc["lat"] = tele.lat;
  doc["lon"] = tele.lon;
  doc["fix"] = tele.gps;
  String out; serializeJson(doc, out);
  sendCors();
  server.send(200, "application/json", out);
}

void handleTarget() {
  StaticJsonDocument<256> in;
  if (deserializeJson(in, server.arg("plain"))) { server.send(400, "text/plain", "bad json"); return; }
  StaticJsonDocument<128> out;
  out["cmd"] = "target";
  out["az"] = in["azimuth"] | 0.0;
  out["alt"] = in["altitude"] | 0.0;
  sendToMega(out);
  sendCors();
  server.send(200, "application/json", "{\"ok\":true}");
}

void handleMove() {
  StaticJsonDocument<256> in;
  if (deserializeJson(in, server.arg("plain"))) { server.send(400, "text/plain", "bad json"); return; }
  StaticJsonDocument<128> out;
  out["cmd"] = "move";
  out["dir"] = in["direction"] | "right";
  out["step"] = in["step"] | "medium";
  sendToMega(out);
  sendCors();
  server.send(200, "application/json", "{\"ok\":true}");
}

void handleCorrection() {
  StaticJsonDocument<256> in;
  if (deserializeJson(in, server.arg("plain"))) { server.send(400, "text/plain", "bad json"); return; }
  StaticJsonDocument<128> out;
  out["cmd"] = "correction";
  out["daz"] = in["azimuthCorrection"] | 0.0;
  out["dalt"] = in["altitudeCorrection"] | 0.0;
  sendToMega(out);
  sendCors();
  server.send(200, "application/json", "{\"ok\":true}");
}

void handleCalibrate() {
  StaticJsonDocument<32> out;
  out["cmd"] = "calibrate";
  sendToMega(out);
  sendCors();
  server.send(200, "application/json", "{\"ok\":true}");
}

void handleCamera() {
  camera_fb_t* fb = esp_camera_fb_get();
  if (!fb) { server.send(500, "text/plain", "capture failed"); return; }
  sendCors();
  server.setContentLength(fb->len);
  server.send(200, "image/jpeg", "");
  WiFiClient client = server.client();
  client.write(fb->buf, fb->len);
  esp_camera_fb_return(fb);
}

void handleStream() {
  WiFiClient client = streamServer.client();

  // GUVENLIK: soket gonderme zaman asimi. Istemci aniden kaybolursa
  // (uygulama oldu, WiFi dustu, navigasyonda soket kapanmadi) write() sonsuza
  // dek bloklayabilir; o zaman icine server.handleClient() koydugumuz dongu de
  // donmez ve TUM API (port 80) cevapsiz kalir. 2 sn SNDTIMEO ile takilan
  // yazim bosa cikar, asagida tespit edip donguden cikar, cihaz toparlanir.
  int sockFd = client.fd();
  if (sockFd >= 0) {
    struct timeval tv;
    tv.tv_sec = 2;
    tv.tv_usec = 0;
    setsockopt(sockFd, SOL_SOCKET, SO_SNDTIMEO, (const void*)&tv, sizeof(tv));
  }

  String head =
    "HTTP/1.1 200 OK\r\n"
    "Access-Control-Allow-Origin: *\r\n"
    "Content-Type: multipart/x-mixed-replace; boundary=otoskopframe\r\n\r\n";
  client.print(head);

  while (client.connected()) {
    camera_fb_t* fb = esp_camera_fb_get();
    if (!fb) break;
    size_t frameLen = fb->len;   // fb_return'den ONCE oku (sonra erisme!)
    client.print("--otoskopframe\r\n");
    client.print("Content-Type: image/jpeg\r\n");
    client.printf("Content-Length: %u\r\n\r\n", frameLen);
    size_t sent = client.write(fb->buf, frameLen);
    client.print("\r\n");
    esp_camera_fb_return(fb);

    // Yazim tam degilse (SNDTIMEO doldu / istemci kayip) yayini bitir ki
    // streamServer yeni baglantilari kabul edebilsin ve API tikanmasin.
    if (sent != frameLen) break;

    // KRITIK: framebuffer geri verildikten SONRA API server'ini pompala.
    // Boylece stream acikken /status, /move, /target, /camera vb. cevap verir
    // (Android yayim sirasinda da bagli kalir ve diger ekranlar calisir).
    server.handleClient();
    pumpMega();             // stream sirasinda telemetri guncel kalsin
    ArduinoOTA.handle();    // stream acikken bile kablosuz yukleme yakalansin
    if (!client.connected()) break;
    delay(40);              // ~25 fps tavani
  }
}

void handleNotFound() {
  sendCors();
  if (server.method() == HTTP_OPTIONS) { server.send(204); return; }
  server.send(404, "text/plain", "not found");
}

// --------------------------- OTA (kablosuz yukleme) ------------------------
void setupOTA() {
  ArduinoOTA.setHostname(MDNS_HOST);   // ArduinoOTA mDNS'i "otoskop.local" olarak baslatir
  ArduinoOTA.setPassword(OTA_PASS);

  ArduinoOTA.onStart([]() {
    // Yukleme baslarken kamerayi kapat: flash erisimi + RAM cakismasin
    esp_camera_deinit();
    Serial.println("OTA: yukleme basliyor...");
  });
  ArduinoOTA.onEnd([]() {
    Serial.println("\nOTA: tamam, yeniden baslatiliyor");
  });
  ArduinoOTA.onProgress([](unsigned int progress, unsigned int total) {
    Serial.printf("OTA: %u%%\r", (progress * 100) / total);
  });
  ArduinoOTA.onError([](ota_error_t error) {
    Serial.printf("OTA HATA [%u]: ", error);
    if (error == OTA_AUTH_ERROR)         Serial.println("kimlik dogrulama");
    else if (error == OTA_BEGIN_ERROR)   Serial.println("baslatma (partition?)");
    else if (error == OTA_CONNECT_ERROR) Serial.println("baglanti");
    else if (error == OTA_RECEIVE_ERROR) Serial.println("alim");
    else if (error == OTA_END_ERROR)     Serial.println("bitis");
  });

  ArduinoOTA.begin();   // bu cagri mDNS'i (otoskop.local) da baslatir

  // App'in otomatik bulmasi icin HTTP servisini mDNS'e ekle (_http._tcp:80)
  MDNS.addService("http", "tcp", 80);

  Serial.println("OTA hazir. App icin: http://" + String(MDNS_HOST) + ".local/  (veya STA IP)");
}

// --------------------------- Bulut OTA (HTTP pull) -------------------------
// version.json'i okur; yeni surum varsa firmware.bin'i indirip kendini gunceller.
// Donus: yeni surum bulunup guncelleme baslatildiysa true (cihaz reboot olur),
// aksi halde false. lastErr doldurulur (durum mesaji).
bool runCloudUpdate(String& msg) {
  if (WiFi.status() != WL_CONNECTED) { msg = "internet yok (STA bagli degil)"; return false; }

  // 1) version.json'i cek
  WiFiClientSecure verClient;
  verClient.setInsecure();           // hobi kullanim: sertifika dogrulamasi yok
  HTTPClient http;
  if (!http.begin(verClient, OTA_VERSION_URL)) { msg = "version.json begin hata"; return false; }
  int code = http.GET();
  if (code != HTTP_CODE_OK) { msg = "version.json HTTP " + String(code); http.end(); return false; }

  String payload = http.getString();
  http.end();

  // UTF-8 BOM (EF BB BF) ve bastaki bosluklari at -> ArduinoJson "{" gorsun
  while (payload.length() >= 3 &&
         (uint8_t)payload[0] == 0xEF && (uint8_t)payload[1] == 0xBB && (uint8_t)payload[2] == 0xBF) {
    payload.remove(0, 3);
  }
  payload.trim();

  StaticJsonDocument<256> doc;
  DeserializationError err = deserializeJson(doc, payload);
  if (err) { msg = "version.json parse hata: " + String(err.c_str()); return false; }

  int latest = doc["version"] | 0;
  String binUrl = doc["url"] | "";
  if (binUrl.length() == 0) { msg = "version.json 'url' bos"; return false; }
  if (latest <= FW_VERSION) { msg = "guncel (v" + String(FW_VERSION) + ")"; return false; }

  // 2) Yeni surum var -> kamerayi kapat, firmware.bin'i cek ve flashla
  Serial.printf("Bulut OTA: v%d -> v%d, indiriliyor...\n", FW_VERSION, latest);
  esp_camera_deinit();               // RAM/flash'i bosalt (TLS + Update icin)

  // Indirme suresince flash LED'i yanip sondur (gorsel gosterge). Progress
  // callback'i her veri parcasinda cagrilir; millis ile ~5Hz blink uretiriz.
  httpUpdate.onProgress([](int cur, int total) {
    static unsigned long lastToggle = 0;
    static bool on = false;
    if (millis() - lastToggle >= 100) {
      lastToggle = millis();
      on = !on;
      digitalWrite(FLASH_LED_PIN, on ? HIGH : LOW);
    }
  });

  WiFiClientSecure upClient;
  upClient.setInsecure();
  httpUpdate.rebootOnUpdate(true);   // basarili olunca otomatik yeniden baslat
  t_httpUpdate_return ret = httpUpdate.update(upClient, binUrl);

  // Buraya donduysek indirme bitti (basarili olsa reboot olurdu) -> LED kapat.
  digitalWrite(FLASH_LED_PIN, LOW);

  switch (ret) {
    case HTTP_UPDATE_OK:
      msg = "guncellendi, reboot";   // genelde buraya gelmeden reboot olur
      return true;
    case HTTP_UPDATE_NO_UPDATES:
      msg = "sunucu guncelleme yok dedi";
      return false;
    case HTTP_UPDATE_FAILED:
    default:
      msg = "OTA HATA: " + httpUpdate.getLastErrorString();
      Serial.println(msg);
      // Guncelleme basarisiz; kamerayi geri ac ki cihaz calismaya devam etsin
      initCamera();
      return false;
  }
}

void handleOtaCheck() {
  sendCors();
  String msg;
  runCloudUpdate(msg);   // yeni surum varsa indirir + reboot eder (buraya donmez)
  // Buraya donduyse guncelleme yapilmadi (guncel, internet yok ya da hata)
  StaticJsonDocument<128> out;
  out["updated"] = false;
  out["message"] = msg;
  out["version"] = FW_VERSION;
  String body; serializeJson(out, body);
  server.send(200, "application/json", body);
}

// --------------------------- Setup / Loop ----------------------------------
void setup() {
  // Brownout dedektorunu kapat: kamera init anindaki akim sicramasinda
  // dusuk voltaj resetini/init hatasini (0x105) onlemenin en sik yolu.
  WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);

  Serial.begin(115200);
  Serial2.begin(115200, SERIAL_8N1, MEGA_RX_PIN, MEGA_TX_PIN);
  pinMode(FLASH_LED_PIN, OUTPUT);
  digitalWrite(FLASH_LED_PIN, LOW);   // flash LED bos durumda kapali
  // Eski WiFi bilgisini flash/NVS'ten temizle
  WiFi.persistent(false);
  WiFi.disconnect(true, true);
  delay(1000);

  if (!initCamera()) {
    Serial.println("Kamera init HATA");
  } else {
    Serial.println("Kamera hazir");
  }

  // AP (Android app icin) + STA (internet/bulut OTA icin) ayni anda
  WiFi.mode(WIFI_AP_STA);
  WiFi.softAP(AP_SSID, AP_PASS);
  Serial.print("AP IP: ");
  Serial.println(WiFi.softAPIP());   // 192.168.4.1

  // Tanimli aglardan (hotspot/ev wifi) hangisi varsa ona baglan. ~15 sn dene.
  for (auto& n : WIFI_NETS) wifiMulti.addAP(n.ssid, n.pass);
  Serial.print("WiFi araniyor");
  unsigned long t0 = millis();
  while (wifiMulti.run() != WL_CONNECTED && millis() - t0 < 15000) {
    delay(300);
    Serial.print('.');
  }
  if (WiFi.status() == WL_CONNECTED) {
    Serial.printf("\nInternet OK, SSID: %s, STA IP: %s\n",
                  WiFi.SSID().c_str(), WiFi.localIP().toString().c_str());
  } else {
    Serial.println("\nWiFi baglanamadi (internet/bulut OTA pasif). Otoskop AP yine acik.");
  }

  setupOTA();

  server.on("/status", HTTP_GET, handleStatus);
  server.on("/ota/check", HTTP_GET, handleOtaCheck);
  server.on("/gps", HTTP_GET, handleGps);
  server.on("/camera", HTTP_GET, handleCamera);
  server.on("/target", HTTP_POST, handleTarget);
  server.on("/move", HTTP_POST, handleMove);
  server.on("/correction", HTTP_POST, handleCorrection);
  server.on("/calibrate", HTTP_POST, handleCalibrate);
  server.onNotFound(handleNotFound);
  server.begin();

  // MJPEG stream ayri port'ta (81) -> API port'unu (80) tikamaz
  streamServer.on("/stream", HTTP_GET, handleStream);
  streamServer.onNotFound([]() {
    streamServer.sendHeader("Access-Control-Allow-Origin", "*");
    streamServer.send(404, "text/plain", "not found");
  });
  streamServer.begin();

  Serial.println("HTTP server basladi (API:80, stream:81)");
}

unsigned long lastWifiCheck = 0;
unsigned long lastMegaDiag = 0;

void loop() {
  ArduinoOTA.handle();
  server.handleClient();
  streamServer.handleClient();   // /stream baglantilarini kabul et
  pumpMega();

  // Mega link teshisi: 2 sn'de bir FTDI seri monitorune ozet bas.
  // bytes=0 -> Serial2 RX pinine HIC sinyal gelmiyor (fiziksel: GND ortak mi?
  // gerilim bolucu dogru hatta mi? Mega TX1=pin18 mi? ESP RX=GPIO13 mi?).
  // bytes artiyor ama lines=0 -> sinyal var ama bozuk (baud/format; iki taraf
  // da 115200 8N1 olmali). Pin13 calismazsa GPIO14'u deneyebilirsin.
  if (millis() - lastMegaDiag > 2000) {
    lastMegaDiag = millis();
#if MEGA_SELFTEST
    // Loopback testi: kendi TX2'mizden yaz; GPIO15->GPIO13 jumper'i varsa
    // RX2'den geri okunur ve byte/lines sayaci artar.
    Serial2.println("{\"selftest\":1}");
    Serial.println("[SELFTEST] Serial2'ye yazildi (GPIO15->GPIO13 jumper bekleniyor)");
#endif
    Serial.printf("[MEGA-DIAG] bytes=%lu lines=%lu (RX=GPIO%d TX=GPIO%d, 115200)\n",
                  megaByteCount, megaLineCount, MEGA_RX_PIN, MEGA_TX_PIN);
  }

  // Baglanti koptuysa ~10 sn'de bir yeniden baglanmayi dene (WiFiMulti)
  if (millis() - lastWifiCheck > 10000) {
    lastWifiCheck = millis();
    if (WiFi.status() != WL_CONNECTED) wifiMulti.run();
  }

  // Periyodik bulut OTA kontrolu (yeni surum yoksa sadece kucuk bir JSON cekilir)
  if (OTA_CHECK_INTERVAL > 0 && millis() - lastOtaCheck > OTA_CHECK_INTERVAL) {
    lastOtaCheck = millis();
    String msg;
    runCloudUpdate(msg);   // yeni surum varsa indirir + reboot eder
    Serial.println("Bulut OTA kontrol: " + msg);
  }
}
