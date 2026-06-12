/*
 * Otoskop - Arduino Mega 2560 firmware
 * --------------------------------------------------------------------------
 * Gorev: sensorler + motorlar. ESP32-CAM ile UART uzerinden (Serial1)
 * satir-sonlu JSON konusur. ESP WiFi/HTTP/kamera tarafini yonetir; Mega
 * sadece "kas + duyu".
 *
 * Sensorler:
 *   - MPU9250 (I2C, 0x68 + manyetometre AK8963 0x0C):
 *       azimut  = tilt-kompanze manyetometre heading (gercek pusula)
 *       altitude = ivmeolcer pitch
 *       gyro     = heading yumusatma (complementary filter)
 *   - GPS (NEO-6M/M8N, TinyGPSPlus, Serial2): lat/lon/fix
 *
 * Motorlar:
 *   - Azimut (pan): SUREKLI DONUS servosu (180'de durmaz, 360 doner). Konum
 *     acisi yok; komut = hiz. MPU heading'e gore KAPALI DONGU surulur.
 *   - Altitude (tilt): NORMAL KONUM servosu (0..180). Yukari limiti kullanici
 *     belirler ({"cmd":"limits","altMax":..}).
 *   Tum hareketler "300ms'de 1 derece" hizinda yumusak ilerler.
 *
 * Haberlesme (Serial1, 115200, satir-sonlu JSON):
 *   ESP -> Mega komut:
 *     {"cmd":"target","az":135.4,"alt":42.8}
 *     {"cmd":"move","dir":"right","step":"medium"}     dir: left|right|up|down
 *     {"cmd":"correction","daz":-0.8,"dalt":0.5}
 *     {"cmd":"calibrate"}                              gyro/mag kalibrasyonu
 *     {"cmd":"calOffset","daz":12.0,"dalt":-3.0}       yon kalibrasyonu (telefonla)
 *     {"cmd":"limits","altMax":60.0}                   altitude yukari limiti
 *     {"cmd":"track","on":true}
 *   Mega -> ESP telemetri (~10 Hz):
 *     {"az":134.8,"alt":42.1,"taz":135.4,"talt":42.8,"sAz":67.4,"sAlt":42.1,
 *      "gps":true,"lat":41.0,"lon":28.9,"imu":true,"trk":true,"lock":false}
 *
 * Kablolama (Mega):
 *   MPU9250 : VCC 3.3V (veya modul 5V toleransli ise 5V), GND, SDA=20, SCL=21
 *   GPS     : VCC 5V, GND, GPS-TX -> Mega RX2(17), GPS-RX -> Mega TX2(16)
 *   ESP link: Mega TX1(18) -> [GERILIM BOLUCU 5V->3.3V] -> ESP RX
 *             Mega RX1(19) <- ESP TX (3.3V, dogrudan okunur)
 *             ORTAK GND sart!
 *   Servo Az: sinyal -> pin 9   (harici 5V besleme, ortak GND)
 *   Servo Alt: sinyal -> pin 10
 *
 * Gerekli kutuphaneler (Arduino Library Manager):
 *   - TinyGPSPlus   (Mikal Hart)
 *   - ArduinoJson   (Benoit Blanchon, 6.x onerilir)
 *   - Servo         (Arduino built-in)
 */

#include <Wire.h>
#include <Servo.h>
#include <ArduinoJson.h>
#include <TinyGPSPlus.h>
#include <EEPROM.h>
#include <math.h>

// ----------------------------- Ayarlar -------------------------------------
// 1: telemetriyi USB seri monitore de yaz (Mega'nin veri uretip uretmedigini
// dogrulamak icin). Arduino IDE Serial Monitor'u 115200'de ac -> {"az":...}
// satirlari akmali. Hata ayiklama bitince 0 yapabilirsin.
#define DEBUG_USB     1

#define MPU_ADDR      0x68
#define AK8963_ADDR   0x0C
#define GYRO_SENS     131.0   // +-250 dps modunda LSB/dps

#define SERVO_AZ_PIN  9
#define SERVO_ALT_PIN 10

// --------------------------- Servo mimarisi --------------------------------
// AZIMUT (yatay/pan): SUREKLI DONUS servosu (DS5160 sw vb. - 180'de durmaz,
//   360 doner). Konum acisi YOK; komut = HIZ. Bu yuzden hedef azimuta gitmek
//   icin MPU heading'e gore KAPALI DONGU surulur: hedefe gelene kadar yavas
//   dondur, gelince durdur. writeMicroseconds ile sürülür:
//     AZ_STOP_US      -> dur (notr). Servon biraz suruyorsa bunu trim et.
//     +/- AZ_SLEW_US  -> notr'den sapma = donus hizi (kucuk = yavas).
//   Donus yonu ters cikarsa AZ_DIR'i -1 yap.
const int   AZ_STOP_US   = 1500;   // surekli servo notr (dur) puls genisligi
const int   AZ_SLEW_US   = 90;     // notr'den sapma (yavas donus). Buyut=hizlan
const int   AZ_DIR       = 1;      // donus yonu (+1 / -1). Ters donerse degistir
const float AZ_TOLERANCE_DEG = 1.0;  // bu kadar yakinsa azimut servosunu durdur

// ALTITUDE (dikey/tilt): NORMAL KONUM servosu (0..180 yazilir). Asagi yonun
//   onemi yok; yukari limiti KULLANICI belirler (uygulamadaki ayar sayfasi ->
//   {"cmd":"limits","altMax":..}). Servo acisi = altitude * ALT_SERVO_SCALE.
const float ALT_SERVO_SCALE  = 1.0;  // mekanik orana gore (1:1 ise 1.0)
const float ALT_MIN_DEG      = 0.0;

// Hareket adimlama: hedefe "300ms'de 1 derece" hizinda yumusak yaklasilir.
const unsigned long STEP_INTERVAL_MS = 300;
const float         STEP_DEG         = 1.0;

const float LOCK_TOLERANCE_DEG = 2.0;  // hedefe bu kadar yakinsa kilit

// --------------------------- Kalibrasyon / limit (kalici, EEPROM) ----------
// Yon kalibrasyonu: telefon (gercek referans) ile MPU okumasi arasindaki fark.
//   azReal = normalize(hamHeading + azOffset);  altReal = hamAlt + altOffset
// Uygulama kalibrasyonda artimsal delta gonderir: {"cmd":"calOffset","daz":..,"dalt":..}
float azOffset  = 0.0;
float altOffset = 0.0;
// Altitude yukari limiti (derece). Uygulamadaki ayar sayfasindan degistirilir.
float altMaxLimit = 90.0;

#define EEPROM_MAGIC 0x4F   // 'O' - EEPROM'da gecerli config var mi?

// Manyetometre hard-iron offset (kalibrasyonla bul, buraya yaz).
// uT cinsinden; varsayilan 0 -> kalibre etmeden de calisir ama sapma olur.
float magOffX = 0.0, magOffY = 0.0, magOffZ = 0.0;
// Yerel manyetik sapma (declination), Istanbul ~ +6 derece. Kendi konumunu gir.
const float MAG_DECLINATION_DEG = 6.0;

// ----------------------------- Durum ---------------------------------------
TinyGPSPlus gps;
Servo servoAz;
Servo servoAlt;

float yaw = 0.0;          // gyro entegre heading (manyetometre ile karisir)
float gzBias = 0.0;
unsigned long lastMicros = 0;

float heading = 0.0;      // HAM azimut (derece, 0..360) - fuzyon cikisi (yaw)
float altitude = 0.0;     // HAM yukseklik acisi (derece) - pitch'ten
float rollDeg = 0.0;      // x ekseni egimi (derece) - debug
float pitchDeg = 0.0;     // y ekseni egimi (derece, ham) - debug

// Kalibrasyon offset'i uygulanmis GERCEK acilar (kapali dongu + telemetri bunu kullanir)
float azReal = 0.0;
float altReal = 0.0;

float targetAz = 0.0;
float targetAlt = 0.0;
bool  haveTarget = false;
bool  tracking = false;
bool  targetLocked = false;

// Adimlanan ara hedef (300ms'de 1 derece ilerler). Servolar buna gore surulur.
float azCmd = 0.0;
float altCmd = 0.0;
unsigned long lastStepMs = 0;

float servoAzPos = 90.0;   // telemetri: azimut komut acisi (azCmd)
float servoAltPos = 0.0;   // telemetri: altitude servo acisi

bool imuOk = false;

// AK8963 hassasiyet duzeltme faktorleri (fabrika ASA)
float magAdjX = 1.0, magAdjY = 1.0, magAdjZ = 1.0;

unsigned long lastTelemetry = 0;
const unsigned long TELEMETRY_INTERVAL_MS = 100;  // 10 Hz

char rxBuf[160];
uint8_t rxLen = 0;

// ----------------------------- I2C yardimcilari ----------------------------
void writeReg(uint8_t addr, uint8_t reg, uint8_t val) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  Wire.write(val);
  Wire.endTransmission();
}

uint8_t readReg(uint8_t addr, uint8_t reg) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  Wire.endTransmission(false);
  Wire.requestFrom((int)addr, 1);
  return Wire.available() ? Wire.read() : 0;
}

bool readBytes(uint8_t addr, uint8_t reg, uint8_t n, uint8_t* buf) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom((int)addr, (int)n) != n) return false;
  for (uint8_t i = 0; i < n; i++) buf[i] = Wire.read();
  return true;
}

// --------------------------- MPU9250 + AK8963 ------------------------------
bool mpuInit() {
  uint8_t who = readReg(MPU_ADDR, 0x75);   // WHO_AM_I (0x71 = 9250, 0x73 = 9255)
  writeReg(MPU_ADDR, 0x6B, 0x00);          // uyandir
  delay(10);
  writeReg(MPU_ADDR, 0x1B, 0x00);          // gyro +-250 dps
  writeReg(MPU_ADDR, 0x1C, 0x00);          // accel +-2g
  // I2C bypass ac -> AK8963'e dogrudan erisim
  writeReg(MPU_ADDR, 0x37, 0x02);
  delay(10);

  // AK8963 fabrika ASA degerlerini oku
  writeReg(AK8963_ADDR, 0x0A, 0x00);       // power down
  delay(10);
  writeReg(AK8963_ADDR, 0x0A, 0x0F);       // fuse ROM access
  delay(10);
  uint8_t asa[3];
  readBytes(AK8963_ADDR, 0x10, 3, asa);
  magAdjX = (asa[0] - 128) / 256.0 + 1.0;
  magAdjY = (asa[1] - 128) / 256.0 + 1.0;
  magAdjZ = (asa[2] - 128) / 256.0 + 1.0;
  writeReg(AK8963_ADDR, 0x0A, 0x00);
  delay(10);
  writeReg(AK8963_ADDR, 0x0A, 0x16);       // 16-bit, 100Hz continuous
  delay(10);

  return (who == 0x71 || who == 0x73 || who != 0x00);
}

bool readAccelGyro(float &ax, float &ay, float &az,
                   float &gx, float &gy, float &gz) {
  uint8_t b[14];
  if (!readBytes(MPU_ADDR, 0x3B, 14, b)) return false;
  int16_t rax = (b[0] << 8) | b[1];
  int16_t ray = (b[2] << 8) | b[3];
  int16_t raz = (b[4] << 8) | b[5];
  int16_t rgx = (b[8] << 8) | b[9];
  int16_t rgy = (b[10] << 8) | b[11];
  int16_t rgz = (b[12] << 8) | b[13];
  if (rax == -32768 || ray == -32768) return false;
  ax = rax; ay = ray; az = raz;
  gx = rgx; gy = rgy; gz = rgz;
  return true;
}

// Manyetometre okuma (uT). AK8963 ekseni MPU eksenine gore farkli:
// mx<-y, my<-x, mz<--z (datasheet hizalama).
bool readMag(float &mx, float &my, float &mz) {
  if (!(readReg(AK8963_ADDR, 0x02) & 0x01)) return false;  // DRDY
  uint8_t b[7];
  if (!readBytes(AK8963_ADDR, 0x03, 7, b)) return false;
  if (b[6] & 0x08) return false;                           // HOFL overflow
  int16_t rx = (b[1] << 8) | b[0];
  int16_t ry = (b[3] << 8) | b[2];
  int16_t rz = (b[5] << 8) | b[4];
  const float scale = 4912.0 / 32760.0;  // uT/LSB (16-bit)
  float mxr = ry * scale * magAdjY;
  float myr = rx * scale * magAdjX;
  float mzr = -rz * scale * magAdjZ;
  mx = mxr - magOffX;
  my = myr - magOffY;
  mz = mzr - magOffZ;
  return true;
}

void calibrateGyro() {
  const int N = 400;
  float ax, ay, az, gx, gy, gz;
  long n = 0; double sum = 0;
  for (int i = 0; i < N; i++) {
    if (readAccelGyro(ax, ay, az, gx, gy, gz)) { sum += gz; n++; }
    delay(2);
  }
  if (n > 0) gzBias = sum / n;
}

// 5 sn boyunca sensoru her yone cevir -> hard-iron offset (min/max ortalama).
void calibrateMag() {
  float mx, my, mz;
  float mnx = 1e6, mxx = -1e6, mny = 1e6, mxy = -1e6, mnz = 1e6, mxz = -1e6;
  unsigned long t0 = millis();
  while (millis() - t0 < 8000) {
    if (readMag(mx, my, mz)) {
      // okurken offset cikariyoruz; ham degeri geri ekleyip min/max bul
      float rx = mx + magOffX, ry = my + magOffY, rz = mz + magOffZ;
      mnx = min(mnx, rx); mxx = max(mxx, rx);
      mny = min(mny, ry); mxy = max(mxy, ry);
      mnz = min(mnz, rz); mxz = max(mxz, rz);
    }
    delay(10);
  }
  magOffX = (mxx + mnx) / 2.0;
  magOffY = (mxy + mny) / 2.0;
  magOffZ = (mxz + mnz) / 2.0;
}

// --------------------------- Servo surme -----------------------------------
// Iki azimut arasi en kisa fark (-180..180).
float shortestAngle(float from, float to) {
  float d = fmod(to - from, 360.0);
  if (d > 180.0)  d -= 360.0;
  if (d < -180.0) d += 360.0;
  return d;
}

float normAz(float a) { a = fmod(a, 360.0); if (a < 0) a += 360.0; return a; }

// 300ms'de bir ara hedefi (azCmd/altCmd) 1 derece nihai hedefe yaklastir.
// Boylece tum hareketler "300ms'de 1 derece" hizinda yumusak ilerler.
void updateStepTargets() {
  if (millis() - lastStepMs < STEP_INTERVAL_MS) return;
  lastStepMs = millis();

  if (!haveTarget) return;   // hedef yokken servolar yerinde kalir

  // Azimut: en kisa yonden 1 derece
  float dAz = shortestAngle(azCmd, targetAz);
  if (fabs(dAz) <= STEP_DEG) azCmd = targetAz;
  else azCmd = normAz(azCmd + (dAz > 0 ? STEP_DEG : -STEP_DEG));

  // Altitude: 1 derece, kullanici limitine kadar
  float tAlt = constrain(targetAlt, ALT_MIN_DEG, altMaxLimit);
  float dAlt = tAlt - altCmd;
  if (fabs(dAlt) <= STEP_DEG) altCmd = tAlt;
  else altCmd += (dAlt > 0 ? STEP_DEG : -STEP_DEG);
  altCmd = constrain(altCmd, ALT_MIN_DEG, altMaxLimit);
}

void driveServos() {
  // --- AZIMUT: surekli donus servosu, MPU heading'e gore kapali dongu ---
  float errAz = shortestAngle(azReal, azCmd);   // azReal -> azCmd farki
  if (haveTarget && fabs(errAz) > AZ_TOLERANCE_DEG) {
    int dir = (errAz > 0) ? 1 : -1;
    servoAz.writeMicroseconds(AZ_STOP_US + AZ_DIR * dir * AZ_SLEW_US);
  } else {
    servoAz.writeMicroseconds(AZ_STOP_US);      // dur (notr)
  }
  servoAzPos = azCmd;                            // telemetri (komut azimutu)

  // --- ALTITUDE: normal konum servosu (kullanici limitiyle) ---
  float servoAngle = altCmd * ALT_SERVO_SCALE;
  servoAngle = constrain(servoAngle, 0.0, 180.0);
  servoAlt.write((int)round(servoAngle));
  servoAltPos = servoAngle;

  // --- Hedef kilidi ---
  // Azimut kapali dongu: GERCEK heading hedefe yakin mi?
  // Altitude acik dongu (konum servosu): komut nihai hedefe ulasti mi?
  if (haveTarget) {
    float lockAz = fabs(shortestAngle(azReal, targetAz));
    float lockAlt = fabs(constrain(targetAlt, ALT_MIN_DEG, altMaxLimit) - altCmd);
    targetLocked = (lockAz < LOCK_TOLERANCE_DEG && lockAlt < LOCK_TOLERANCE_DEG);
  }
}

// --------------------------- EEPROM (kalici config) ------------------------
void saveConfig() {
  EEPROM.update(0, EEPROM_MAGIC);
  EEPROM.put(1, azOffset);
  EEPROM.put(1 + sizeof(float), altOffset);
  EEPROM.put(1 + 2 * sizeof(float), altMaxLimit);
}

void loadConfig() {
  if (EEPROM.read(0) != EEPROM_MAGIC) return;   // hic kaydedilmemis -> varsayilan
  EEPROM.get(1, azOffset);
  EEPROM.get(1 + sizeof(float), altOffset);
  EEPROM.get(1 + 2 * sizeof(float), altMaxLimit);
  if (isnan(azOffset)) azOffset = 0.0;
  if (isnan(altOffset)) altOffset = 0.0;
  if (isnan(altMaxLimit) || altMaxLimit <= 0) altMaxLimit = 90.0;
}

// --------------------------- Komut isleme ----------------------------------
void handleCommand(const char* line) {
#if DEBUG_USB
  // ESP'den gelen ham komutu USB seri monitore yansit. Buton/kalibre/hedef
  // basildiginda burada gorunuyorsa ESP->Mega yonu (GPIO15 -> Mega RX1/pin19)
  // calisiyor demektir. Hic gorunmuyorsa o kablo/GND yok.
  Serial.print(F("[CMD] "));
  Serial.println(line);
#endif
  StaticJsonDocument<200> doc;
  DeserializationError err = deserializeJson(doc, line);
  if (err) {
#if DEBUG_USB
    Serial.println(F("[CMD] JSON parse HATA"));
#endif
    return;
  }
  const char* cmd = doc["cmd"] | "";

  if (strcmp(cmd, "target") == 0) {
    targetAz = normAz(doc["az"] | targetAz);
    targetAlt = constrain((float)(doc["alt"] | targetAlt), ALT_MIN_DEG, altMaxLimit);
    azCmd = azReal;   // surekli azimut: ara hedef GERCEK heading'den baslasin
    haveTarget = true;
    tracking = true;
    targetLocked = false;
  } else if (strcmp(cmd, "correction") == 0) {
    targetAz = normAz(targetAz + (float)(doc["daz"] | 0.0));
    targetAlt = constrain(targetAlt + (float)(doc["dalt"] | 0.0), ALT_MIN_DEG, altMaxLimit);
    haveTarget = true;
  } else if (strcmp(cmd, "move") == 0) {
    const char* dir = doc["dir"] | "";
    const char* step = doc["step"] | "medium";
    float s = 1.0;
    if (strcmp(step, "small") == 0) s = 0.5;
    else if (strcmp(step, "large") == 0) s = 5.0;
    else s = 2.0;
    if (!haveTarget) { targetAz = azReal; targetAlt = altReal; azCmd = azReal; haveTarget = true; }
    if (strcmp(dir, "left") == 0)  targetAz -= s;
    if (strcmp(dir, "right") == 0) targetAz += s;
    if (strcmp(dir, "up") == 0)    targetAlt += s;
    if (strcmp(dir, "down") == 0)  targetAlt -= s;
    targetAz = normAz(targetAz);
    targetAlt = constrain(targetAlt, ALT_MIN_DEG, altMaxLimit);
  } else if (strcmp(cmd, "track") == 0) {
    tracking = doc["on"] | false;
  } else if (strcmp(cmd, "calibrate") == 0) {
    calibrateGyro();
    calibrateMag();
    yaw = heading;
  } else if (strcmp(cmd, "calOffset") == 0) {
    // Yon kalibrasyonu: telefon (gercek) ile MPU farkinin ARTIMSAL eklenmesi.
    azOffset = normAz(azOffset + (float)(doc["daz"] | 0.0));
    altOffset += (float)(doc["dalt"] | 0.0);
    saveConfig();
  } else if (strcmp(cmd, "limits") == 0) {
    // Altitude yukari limiti (kullanici belirler). Hedefi de hemen kis.
    altMaxLimit = constrain((float)(doc["altMax"] | altMaxLimit), 1.0, 180.0);
    targetAlt = constrain(targetAlt, ALT_MIN_DEG, altMaxLimit);
    saveConfig();
  }
}

void pollSerialCommands() {
  while (Serial1.available()) {
    char c = Serial1.read();
    if (c == '\n' || c == '\r') {
      if (rxLen > 0) { rxBuf[rxLen] = '\0'; handleCommand(rxBuf); rxLen = 0; }
    } else if (rxLen < sizeof(rxBuf) - 1) {
      rxBuf[rxLen++] = c;
    } else {
      rxLen = 0;  // tasma -> sifirla
    }
  }
}

// --------------------------- Telemetri -------------------------------------
void sendTelemetry() {
  StaticJsonDocument<512> doc;
  // az/alt = kalibrasyon offset'i uygulanmis GERCEK acilar (uygulama bunu gosterir)
  doc["az"] = round(azReal * 10) / 10.0;
  doc["alt"] = round(altReal * 10) / 10.0;
  doc["taz"] = round(targetAz * 10) / 10.0;
  doc["talt"] = round(targetAlt * 10) / 10.0;
  doc["sAz"] = round(servoAzPos * 10) / 10.0;
  doc["sAlt"] = round(servoAltPos * 10) / 10.0;
  // Ham yonelim acilari (x/y/z): roll, pitch, yaw. yaw = HAM fuzyon heading.
  doc["roll"] = round(rollDeg * 10) / 10.0;
  doc["pitch"] = round(pitchDeg * 10) / 10.0;
  doc["yaw"] = round(heading * 10) / 10.0;
  // Kalibrasyon/limit durumu (uygulama gosterimi icin)
  doc["azOff"] = round(azOffset * 10) / 10.0;
  doc["altOff"] = round(altOffset * 10) / 10.0;
  doc["altMax"] = round(altMaxLimit * 10) / 10.0;
  doc["gps"] = gps.location.isValid();
  if (gps.location.isValid()) {
    doc["lat"] = gps.location.lat();
    doc["lon"] = gps.location.lng();
  }
  doc["imu"] = imuOk;
  doc["trk"] = tracking;
  doc["lock"] = targetLocked;
  serializeJson(doc, Serial1);
  Serial1.print('\n');
#if DEBUG_USB
  // Ayni telemetriyi USB seri monitore de yansit (Mega debug). Boylece Mega'nin
  // gercekten veri uretip uretmedigini (imu/gps dahil) dogrudan gorursun.
  // ESP'ye giden veri ayni; bu sadece kopyasi.
  serializeJson(doc, Serial);
  Serial.println();
#endif
}

// --------------------------- Sensor fuzyonu --------------------------------
void updateOrientation() {
  float ax, ay, az, gx, gy, gz;
  if (!readAccelGyro(ax, ay, az, gx, gy, gz)) {
    imuOk = false;
    // Otomatik kurtarma: MPU I2C bus'tan dustuyse (gevsek kablo, parazit,
    // acilis sirasi) saniyede bir yeniden baslatmayi dene. Boylece bir kez
    // hata olsa bile baglanti duzelince IMU kendiliginden geri gelir
    // (eskiden imuOk kalici false kaliyordu -> uygulamada surekli kirmizi).
    static unsigned long lastReinit = 0;
    if (millis() - lastReinit > 1000) {
      lastReinit = millis();
      mpuInit();
    }
    return;
  }
  imuOk = true;

  unsigned long now = micros();
  float dt = (now - lastMicros) / 1000000.0;
  lastMicros = now;
  if (dt <= 0 || dt > 0.5) dt = 0.01;

  // Pitch/roll (rad)
  float pitch = atan2(-ax, sqrt(ay * ay + az * az));
  float roll  = atan2(ay, az);
  pitchDeg = pitch * 180.0 / PI;    // ham pitch (debug)
  rollDeg  = roll * 180.0 / PI;     // ham roll  (debug)
  altitude = pitchDeg;              // ham yukseklik (asagi yon serbest)

  // Tilt-kompanze manyetometre heading
  float mx, my, mz;
  static float magHeading = 0.0;
  if (readMag(mx, my, mz)) {
    float xh = mx * cos(pitch) + mz * sin(pitch);
    float yh = mx * sin(roll) * sin(pitch) + my * cos(roll)
             - mz * sin(roll) * cos(pitch);
    float h = atan2(-yh, xh) * 180.0 / PI + MAG_DECLINATION_DEG;
    if (h < 0) h += 360.0;
    magHeading = h;
  }

  // Gyro Z entegrasyonu + complementary filter (drift'i mag ile duzelt)
  float gzDps = (gz - gzBias) / GYRO_SENS;
  yaw += gzDps * dt;
  float diff = magHeading - yaw;
  while (diff > 180) diff -= 360;
  while (diff < -180) diff += 360;
  yaw += diff * 0.02;   // %2 manyetometre duzeltmesi/loop
  heading = fmod(yaw, 360.0); if (heading < 0) heading += 360.0;

  // Kalibrasyon offset'ini uygula -> GERCEK (dunya) acilari.
  azReal = normAz(heading + azOffset);
  altReal = altitude + altOffset;
}

// --------------------------- Setup / Loop ----------------------------------
void setup() {
  Serial.begin(115200);          // USB debug
  Serial1.begin(115200);         // ESP link
  Serial2.begin(9600);           // GPS (NEO-6M varsayilan 9600)
  Wire.begin();
  // 100 kHz: dupont/uzun kablolarda 400 kHz'e gore cok daha guvenilir.
  // MPU9250'den okuma araliklarinda hata (imuOk=false) yasayanlarin en sik
  // cozumu budur; 10 Hz telemetri icin hiz fazlasiyla yeterli.
  Wire.setClock(100000);

  loadConfig();   // kalici azOffset/altOffset/altMaxLimit

  // Surekli servoda 544..2400us tam araligi kullanabilmek icin min/max ver.
  servoAz.attach(SERVO_AZ_PIN, 500, 2500);
  servoAlt.attach(SERVO_ALT_PIN);
  servoAz.writeMicroseconds(AZ_STOP_US);   // azimut: dur (notr)
  servoAlt.write(0);                       // altitude: en alt

  imuOk = mpuInit();
  delay(50);
  calibrateGyro();
  lastMicros = micros();
  lastStepMs = millis();
  Serial.println(imuOk ? F("MPU9250 hazir") : F("MPU9250 bulunamadi"));
}

void loop() {
#if DEBUG_USB
  // Kalp atisi: loop'un en BASINDA, her saniye yaz. Boylece:
  //  - hic "[LOOP]" gormuyorsan -> ya yeni kod yuklenmedi ya da loop hic
  //    calismiyor (setup'ta takildi).
  //  - "[LOOP]" surekli artiyorsa -> loop donuyor; asagida telemetri de
  //    akmali. "[LOOP]" bir kez cikip duruyorsa -> loop govdesi takiliyor
  //    (updateOrientation/I2C). imuOk degerini de gosterir.
  static unsigned long lastBeat = 0;
  static unsigned long loopCount = 0;
  loopCount++;
  if (millis() - lastBeat > 1000) {
    lastBeat = millis();
    Serial.print(F("[LOOP] n="));
    Serial.print(loopCount);
    Serial.print(F(" imuOk="));
    Serial.println(imuOk ? 1 : 0);
  }
#endif

  // GPS beslemesi
  while (Serial2.available()) gps.encode(Serial2.read());

  updateOrientation();
  pollSerialCommands();
  updateStepTargets();   // 300ms'de 1 derece ara hedef ilerlemesi
  driveServos();

  if (millis() - lastTelemetry >= TELEMETRY_INTERVAL_MS) {
    lastTelemetry = millis();
    sendTelemetry();
  }
}
