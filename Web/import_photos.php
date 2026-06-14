<?php
/**
 * Otoskop-Photos/ klasöründeki fotoğrafları, dosya adındaki zaman damgasına
 * (YYYYMMDD_HHMMSS) göre meta veri üreterek media tablosuna toplu ekler ve
 * dosyaları Web/uploads/ altına kopyalar.
 *
 * Kullanım (komut satırı):
 *   php Web/import_photos.php [username] [kaynak_klasor]
 *   - username      : medyayı sahiplenecek kullanıcı (varsayılan: admin)
 *   - kaynak_klasor : varsayılan ../Otoskop-Photos
 *
 * Aynı dosya tekrar çalıştırılırsa (original_name + captured_at eşleşmesi)
 * atlanır; yani güvenle birden çok kez çağrılabilir.
 */

require __DIR__ . '/upload_lib.php';

$cli = (php_sapi_name() === 'cli');
$username = $argv[1] ?? 'admin';
$srcDir   = $argv[2] ?? (__DIR__ . '/../Otoskop-Photos');
$srcDir   = rtrim(str_replace('\\', '/', $srcDir), '/');

function out(string $s): void { echo $s . "\n"; }

if (!is_dir($srcDir)) {
    fwrite(STDERR, "Kaynak klasör bulunamadı: {$srcDir}\n");
    exit(1);
}

$c = db();

// Sahibi bul
$stmt = $c->prepare('SELECT id FROM users WHERE username = ? LIMIT 1');
$stmt->bind_param('s', $username);
$stmt->execute();
$user = $stmt->get_result()->fetch_assoc();
if (!$user) {
    fwrite(STDERR, "Kullanıcı bulunamadı: {$username}\n");
    exit(1);
}
$userId = (int)$user['id'];

$uploadsDir = __DIR__ . '/uploads';
if (!is_dir($uploadsDir) && !@mkdir($uploadsDir, 0775, true) && !is_dir($uploadsDir)) {
    fwrite(STDERR, "uploads/ klasörü oluşturulamadı.\n");
    exit(1);
}

$extMime = [
    'jpg'  => 'image/jpeg',
    'jpeg' => 'image/jpeg',
    'png'  => 'image/png',
    'webp' => 'image/webp',
];

// Dosyaları topla
$files = [];
foreach (scandir($srcDir) as $f) {
    if ($f === '.' || $f === '..') continue;
    $path = $srcDir . '/' . $f;
    if (!is_file($path)) continue;
    $ext = strtolower(pathinfo($f, PATHINFO_EXTENSION));
    if (!isset($extMime[$ext])) continue;
    $files[] = $f;
}
sort($files); // ada/zamana göre sırala

if (!$files) {
    out("İçe aktarılacak fotoğraf bulunamadı: {$srcDir}");
    exit(0);
}

/**
 * Dosya adından (YYYYMMDD_HHMMSS) çekim zamanını çözer. Çözemezse dosyanın
 * değiştirilme zamanını kullanır.
 */
function parse_captured(string $name, string $path): array
{
    if (preg_match('/(\d{4})(\d{2})(\d{2})[_\-]?(\d{2})(\d{2})(\d{2})/', $name, $mm)) {
        $ts = mktime((int)$mm[4], (int)$mm[5], (int)$mm[6], (int)$mm[2], (int)$mm[3], (int)$mm[1]);
        if ($ts) return [$ts, true];
    }
    return [filemtime($path) ?: time(), false];
}

// Önce tüm zaman damgalarını çöz, sonra oturumlara (session) ayır
$items = [];
foreach ($files as $f) {
    [$ts, $fromName] = parse_captured($f, $srcDir . '/' . $f);
    $items[] = ['name' => $f, 'ts' => $ts, 'from_name' => $fromName];
}
usort($items, fn($a, $b) => $a['ts'] <=> $b['ts']);

// 5 dakikadan büyük boşlukta yeni oturum başlat
$SESSION_GAP = 300;
$sessionId = null;
$prevTs = null;
foreach ($items as $i => &$it) {
    if ($prevTs === null || ($it['ts'] - $prevTs) > $SESSION_GAP) {
        $sessionId = 'OTO-' . date('Ymd-Hi', $it['ts']);
    }
    $it['session_id'] = $sessionId;
    $prevTs = $it['ts'];
}
unset($it);

$insert = $c->prepare(
    'INSERT INTO media
     (user_id, type, file_name, original_name, mime, file_size,
      target_name, object_type, width, height, session_id, notes, captured_at)
     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)'
);

$exists = $c->prepare(
    'SELECT id FROM media WHERE user_id = ? AND original_name = ? AND captured_at = ? LIMIT 1'
);

$added = 0;
$skipped = 0;
foreach ($items as $it) {
    $orig = $it['name'];
    $src  = $srcDir . '/' . $orig;
    $ext  = strtolower(pathinfo($orig, PATHINFO_EXTENSION));
    $mime = $extMime[$ext] ?? 'image/jpeg';
    $type = 'photo';
    $capturedAt = date('Y-m-d H:i:s', $it['ts']);

    // Zaten içe aktarılmış mı?
    $exists->bind_param('iss', $userId, $orig, $capturedAt);
    $exists->execute();
    if ($exists->get_result()->fetch_assoc()) {
        out("ATLANDI (zaten var): {$orig}");
        $skipped++;
        continue;
    }

    // uploads/ içine kopyala (orijinali korunur)
    $destExt  = $ext === 'jpeg' ? 'jpg' : $ext;
    $fname    = date('Ymd_His', $it['ts']) . '_' . bin2hex(random_bytes(6)) . '.' . $destExt;
    $dest     = $uploadsDir . '/' . $fname;
    if (!@copy($src, $dest)) {
        fwrite(STDERR, "KOPYALANAMADI: {$orig}\n");
        continue;
    }

    $size = (int)filesize($dest);
    $width = $height = null;
    $info = @getimagesize($dest);
    if ($info) {
        $width  = $info[0];
        $height = $info[1];
    }

    $target  = 'Gözlem ' . date('d.m.Y H:i', $it['ts']);
    $objType = 'teleskopik';
    $session = $it['session_id'];
    $notes   = $it['from_name']
        ? "Otoskop-Photos klasöründen içe aktarıldı (çekim zamanı dosya adından)."
        : "Otoskop-Photos klasöründen içe aktarıldı (çekim zamanı dosya tarihinden).";

    $insert->bind_param(
        'issssissiisss',
        $userId, $type, $fname, $orig, $mime, $size,
        $target, $objType, $width, $height, $session, $notes, $capturedAt
    );
    if (!$insert->execute()) {
        @unlink($dest);
        fwrite(STDERR, "DB HATASI ({$orig}): " . $insert->error . "\n");
        continue;
    }

    out(sprintf("EKLENDI #%d  %s  ->  %s  [%s]  %s",
        $insert->insert_id, $orig, $fname, $session, $capturedAt));
    $added++;
}

out("");
out("Bitti. Eklenen: {$added}, atlanan: {$skipped}, sahibi: {$username} (id {$userId}).");
