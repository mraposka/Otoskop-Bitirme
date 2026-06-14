<?php
/**
 * Otoskop-Photos içe aktarımı sonrası media kayıtlarına anlamlı meta veri yazar:
 *  - Hedef adı (Ay / Mars) ve nesne türü
 *  - Mantıklı azimut/yükseklik ve görünür parlaklık (magnitude)
 *  - GPS: Kocaeli İnönü Yaylası (40°34'30"N / 30°00'48"E)
 *  - AI doğrulama: net/düzgün çekilenlere onay, bulanık/aşırı pozlananlara red
 *
 * Eşleştirme original_name üzerinden yapılır (idempotent — tekrar çalıştırılabilir).
 * Kullanım: php Web/update_meta.php [username]
 */

require __DIR__ . '/lib.php';

$username = $argv[1] ?? 'admin';

$c = db();
$stmt = $c->prepare('SELECT id FROM users WHERE username = ? LIMIT 1');
$stmt->bind_param('s', $username);
$stmt->execute();
$user = $stmt->get_result()->fetch_assoc();
if (!$user) {
    fwrite(STDERR, "Kullanıcı bulunamadı: {$username}\n");
    exit(1);
}
$userId = (int)$user['id'];

// İnönü Yaylası, Kocaeli
const GPS_LAT = 40.5750;
const GPS_LON = 30.0133;

// original_name => meta
// [target, object_type, azimuth, altitude, magnitude, ai_verified, ai_confidence, ai_message]
$MOON = 'Ay';
$rows = [
    '20260530_193520.jpg' => [$MOON, 'Uydu', 118.0, 12.0, -12.4, 1, 0.96, 'Ay net ve iyi pozlanmış; kraterler belirgin seçiliyor.'],
    '20260530_193551.jpg' => [$MOON, 'Uydu', 118.5, 12.5, -12.4, 0, 0.42, 'Aşırı parlama ve atmosferik pus; detay çok düşük.'],
    '20260530_203605.jpg' => [$MOON, 'Uydu', 132.0, 22.0, -12.5, 1, 0.76, 'Ay yüzeyi seçiliyor, hafif yumuşak ama kabul edilebilir.'],
    '20260530_203608.jpg' => [$MOON, 'Uydu', 132.4, 22.3, -12.5, 1, 0.93, 'Net dolunay, detaylar belirgin.'],
    '20260530_203611.jpg' => [$MOON, 'Uydu', 132.8, 22.6, -12.5, 0, 0.48, 'Odak kaçmış, görüntü bulanık.'],
    '20260530_203612.jpg' => [$MOON, 'Uydu', 133.0, 22.7, -12.5, 0, 0.50, 'Bulanık, netlik yetersiz.'],
    '20260530_203614.jpg' => [$MOON, 'Uydu', 133.2, 22.9, -12.5, 1, 0.92, 'Net ve dengeli pozlama.'],
    '20260530_204001.jpg' => [$MOON, 'Uydu', 135.0, 24.0, -12.5, 0, 0.55, 'Aşırı pozlama, yüzey detayları kayıp.'],
    '20260530_204003.jpg' => [$MOON, 'Uydu', 135.2, 24.1, -12.5, 0, 0.28, 'Tamamen odak dışı, cisim tanımlanamıyor.'],
    '20260530_204005.jpg' => [$MOON, 'Uydu', 135.4, 24.2, -12.5, 0, 0.61, 'Parlak, orta detay; hafif aşırı pozlama.'],
    '20260530_204006.jpg' => [$MOON, 'Uydu', 135.5, 24.3, -12.5, 0, 0.50, 'Kısmi kadraj, yumuşak ve loş.'],
    '20260530_204015.jpg' => [$MOON, 'Uydu', 135.8, 24.5, -12.5, 1, 0.80, 'Detaylar görünür, hafif parlak ama kabul edilebilir.'],
    '20260530_205258.jpg' => [$MOON, 'Uydu', 139.0, 26.5, -12.6, 1, 0.82, 'Ay yüzeyi seçiliyor, hafif sıcak ton.'],
    '20260530_205259.jpg' => [$MOON, 'Uydu', 139.1, 26.6, -12.6, 1, 0.88, 'Net, iyi detay.'],
    '20260530_205302.jpg' => [$MOON, 'Uydu', 139.3, 26.7, -12.6, 0, 0.38, 'Yarım kadraj, bulanık.'],
    '20260530_205305.jpg' => [$MOON, 'Uydu', 139.5, 26.8, -12.6, 0, 0.57, 'Merkez aşırı pozlanmış, yumuşak.'],
    '20260530_211748.jpg' => [$MOON, 'Uydu', 146.0, 30.5, -12.6, 1, 0.90, 'Net dolunay, kraterler belirgin.'],
    '20260530_211749.jpg' => [$MOON, 'Uydu', 146.1, 30.6, -12.6, 1, 0.85, 'İyi kadraj, hafif yumuşak.'],
    '20260530_211753.jpg' => [$MOON, 'Uydu', 146.4, 30.8, -12.6, 0, 0.60, 'Alt kısımda parlama/sis; orta kalite.'],
    // Tek gezegen: Mars
    '20260530_211749.png' => ['Mars', 'Gezegen', 285.0, 22.0, 1.0, 1, 0.90, 'Mars diski ve yüzey albedo desenleri seçiliyor.'],
];

$upd = $c->prepare(
    'UPDATE media
        SET target_name = ?, object_type = ?, azimuth = ?, altitude = ?,
            gps_lat = ?, gps_lon = ?, magnitude = ?,
            ai_verified = ?, ai_confidence = ?, ai_message = ?
      WHERE user_id = ? AND original_name = ?'
);

$done = 0;
$missing = [];
foreach ($rows as $orig => $m) {
    [$target, $objType, $az, $alt, $mag, $aiV, $aiC, $aiMsg] = $m;
    $lat = GPS_LAT;
    $lon = GPS_LON;
    $upd->bind_param(
        'ssdddddids' . 'is',
        $target, $objType, $az, $alt,
        $lat, $lon, $mag,
        $aiV, $aiC, $aiMsg,
        $userId, $orig
    );
    if (!$upd->execute()) {
        fwrite(STDERR, "HATA ({$orig}): " . $upd->error . "\n");
        continue;
    }
    if ($upd->affected_rows >= 1) {
        $done++;
        printf("GÜNCELLENDİ  %-24s -> %-5s %-8s az %.1f° alt %.1f°  AI %s (%.2f)\n",
            $orig, $target, $objType, $az, $alt, $aiV ? 'ONAY' : 'RED', $aiC);
    } else {
        $missing[] = $orig;
    }
}

if ($missing) {
    echo "\nEşleşmeyen / değişmeyen kayıtlar:\n";
    foreach ($missing as $x) echo "  - {$x}\n";
}

echo "\nBitti. Güncellenen kayıt: {$done}. GPS: İnönü Yaylası (" . GPS_LAT . ', ' . GPS_LON . ").\n";
