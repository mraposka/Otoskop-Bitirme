<?php
require_once __DIR__ . '/lib.php';

/**
 * Yüklenen bir dosyayı doğrular, uploads/ altına kaydeder ve media
 * tablosuna meta verisiyle ekler. Hem web formu hem cihaz API'si kullanır.
 *
 * @param int   $userId  sahibi
 * @param array $file    $_FILES['x'] biçiminde dizi
 * @param array $meta    target_name, object_type, azimuth, altitude,
 *                       gps_lat, gps_lon, magnitude, ai_verified,
 *                       ai_confidence, ai_message, fps, duration_sec,
 *                       session_id, notes, captured_at
 * @return array{ok:bool, id?:int, error?:string, file_name?:string}
 */
function store_media_upload(int $userId, array $file, array $meta): array
{
    if (!isset($file['error']) || is_array($file['error'])) {
        return ['ok' => false, 'error' => 'Geçersiz dosya gönderimi.'];
    }
    switch ($file['error']) {
        case UPLOAD_ERR_OK:
            break;
        case UPLOAD_ERR_INI_SIZE:
        case UPLOAD_ERR_FORM_SIZE:
            return ['ok' => false, 'error' => 'Dosya çok büyük (sunucu limiti). php.ini upload_max_filesize / post_max_size artırılmalı.'];
        case UPLOAD_ERR_NO_FILE:
            return ['ok' => false, 'error' => 'Dosya seçilmedi.'];
        default:
            return ['ok' => false, 'error' => 'Yükleme hatası (kod ' . $file['error'] . ').'];
    }

    if ($file['size'] > MAX_UPLOAD_BYTES) {
        return ['ok' => false, 'error' => 'Dosya boyutu ' . human_size(MAX_UPLOAD_BYTES) . ' sınırını aşıyor.'];
    }

    $finfo = new finfo(FILEINFO_MIME_TYPE);
    $mime = (string)$finfo->file($file['tmp_name']);

    $type = null;
    $ext = null;
    if (in_array($mime, ALLOWED_IMAGE, true)) {
        $type = 'photo';
        $ext = ['image/jpeg' => 'jpg', 'image/png' => 'png', 'image/webp' => 'webp'][$mime];
    } elseif (in_array($mime, ALLOWED_VIDEO, true)) {
        $type = 'video';
        $ext = ['video/mp4' => 'mp4', 'video/webm' => 'webm', 'video/x-msvideo' => 'avi',
            'video/avi' => 'avi', 'video/quicktime' => 'mov'][$mime] ?? 'mp4';
    } else {
        return ['ok' => false, 'error' => 'Desteklenmeyen dosya türü: ' . $mime . ' (JPEG/PNG/WEBP veya MP4/WEBM/AVI/MOV).'];
    }

    $dir = __DIR__ . '/uploads';
    if (!is_dir($dir) && !@mkdir($dir, 0775, true) && !is_dir($dir)) {
        return ['ok' => false, 'error' => 'uploads/ klasörü oluşturulamadı.'];
    }

    $fname = date('Ymd_His') . '_' . bin2hex(random_bytes(6)) . '.' . $ext;
    $dest = $dir . '/' . $fname;
    if (!move_uploaded_file($file['tmp_name'], $dest)) {
        // API tarafında file_put_contents ile yazılan geçici dosyalar için fallback
        if (!@rename($file['tmp_name'], $dest)) {
            return ['ok' => false, 'error' => 'Dosya kaydedilemedi.'];
        }
    }

    // Görüntü boyutları
    $width = $height = null;
    if ($type === 'photo') {
        $info = @getimagesize($dest);
        if ($info) {
            $width = $info[0];
            $height = $info[1];
        }
    }

    $size = (int)filesize($dest);
    $original = isset($file['name']) ? substr((string)$file['name'], 0, 255) : null;

    $target = ($meta['target_name'] ?? '') !== '' ? substr((string)$meta['target_name'], 0, 120) : null;
    $objType = ($meta['object_type'] ?? '') !== '' ? substr((string)$meta['object_type'], 0, 40) : null;
    $az = isset($meta['azimuth']) && $meta['azimuth'] !== '' ? (float)$meta['azimuth'] : null;
    $alt = isset($meta['altitude']) && $meta['altitude'] !== '' ? (float)$meta['altitude'] : null;
    $lat = isset($meta['gps_lat']) && $meta['gps_lat'] !== '' ? (float)$meta['gps_lat'] : null;
    $lon = isset($meta['gps_lon']) && $meta['gps_lon'] !== '' ? (float)$meta['gps_lon'] : null;
    $magn = isset($meta['magnitude']) && $meta['magnitude'] !== '' ? (float)$meta['magnitude'] : null;
    $aiVer = !empty($meta['ai_verified']) ? 1 : 0;
    $aiConf = isset($meta['ai_confidence']) && $meta['ai_confidence'] !== '' ? (float)$meta['ai_confidence'] : null;
    $aiMsg = ($meta['ai_message'] ?? '') !== '' ? substr((string)$meta['ai_message'], 0, 255) : null;
    $fps = isset($meta['fps']) && $meta['fps'] !== '' ? (float)$meta['fps'] : null;
    $dur = isset($meta['duration_sec']) && $meta['duration_sec'] !== '' ? (float)$meta['duration_sec'] : null;
    $sess = ($meta['session_id'] ?? '') !== '' ? substr((string)$meta['session_id'], 0, 64) : null;
    $notes = ($meta['notes'] ?? '') !== '' ? (string)$meta['notes'] : null;

    $captured = null;
    if (!empty($meta['captured_at'])) {
        $ts = strtotime((string)$meta['captured_at']);
        if ($ts) $captured = date('Y-m-d H:i:s', $ts);
    }
    if ($captured === null) {
        $captured = date('Y-m-d H:i:s');
    }

    $stmt = db()->prepare(
        'INSERT INTO media
         (user_id, type, file_name, original_name, mime, file_size,
          target_name, object_type, azimuth, altitude, gps_lat, gps_lon,
          magnitude, ai_verified, ai_confidence, ai_message, width, height,
          fps, duration_sec, session_id, notes, captured_at)
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)'
    );
    $stmt->bind_param(
        'issssissdddddidsiiddsss',
        $userId, $type, $fname, $original, $mime, $size,
        $target, $objType, $az, $alt, $lat, $lon,
        $magn, $aiVer, $aiConf, $aiMsg, $width, $height,
        $fps, $dur, $sess, $notes, $captured
    );
    if (!$stmt->execute()) {
        @unlink($dest);
        return ['ok' => false, 'error' => 'Veritabanı kaydı başarısız: ' . $stmt->error];
    }

    return ['ok' => true, 'id' => $stmt->insert_id, 'file_name' => $fname];
}
