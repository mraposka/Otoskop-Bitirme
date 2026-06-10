<?php
/**
 * Cihaz/Mobil yükleme API'si (gelecekte mobil uygulamanın kullanması için).
 *
 * POST multipart/form-data:
 *   - api_key   : config.php'deki API_KEY
 *   - username  : medyayı sahiplenecek kullanıcı (kayıtlı olmalı)
 *   - media     : dosya (foto/video)
 *   - + meta alanları: target_name, object_type, azimuth, altitude,
 *     gps_lat, gps_lon, magnitude, ai_verified, ai_confidence, ai_message,
 *     fps, duration_sec, session_id, notes, captured_at
 *
 * Yanıt: JSON { ok, id?, file_name?, url?, error? }
 */
require_once __DIR__ . '/../upload_lib.php';

header('Content-Type: application/json; charset=utf-8');

function api_out(array $data, int $code = 200): void
{
    http_response_code($code);
    echo json_encode($data, JSON_UNESCAPED_UNICODE);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    api_out(['ok' => false, 'error' => 'POST bekleniyor'], 405);
}

$key = $_POST['api_key'] ?? ($_SERVER['HTTP_X_API_KEY'] ?? '');
if (!hash_equals(API_KEY, (string)$key)) {
    api_out(['ok' => false, 'error' => 'Geçersiz API anahtarı'], 401);
}

$username = trim($_POST['username'] ?? '');
if ($username === '') {
    api_out(['ok' => false, 'error' => 'username gerekli'], 400);
}
$stmt = db()->prepare('SELECT id FROM users WHERE username = ? LIMIT 1');
$stmt->bind_param('s', $username);
$stmt->execute();
$user = $stmt->get_result()->fetch_assoc();
if (!$user) {
    api_out(['ok' => false, 'error' => 'Kullanıcı bulunamadı: ' . $username], 404);
}

if (empty($_FILES['media']) || $_FILES['media']['error'] === UPLOAD_ERR_NO_FILE) {
    api_out(['ok' => false, 'error' => 'media dosyası gerekli'], 400);
}

$res = store_media_upload((int)$user['id'], $_FILES['media'], $_POST);
if (!$res['ok']) {
    api_out($res, 422);
}

$base = (isset($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off' ? 'https' : 'http')
    . '://' . ($_SERVER['HTTP_HOST'] ?? 'localhost');
$dir = rtrim(dirname(dirname($_SERVER['SCRIPT_NAME'])), '/');
$res['url'] = $base . $dir . '/uploads/' . $res['file_name'];

api_out($res);
