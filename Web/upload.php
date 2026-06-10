<?php
require_once __DIR__ . '/upload_lib.php';

$u = require_login();
$errors = [];

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    // post_max_size aşıldıysa PHP $_POST ve $_FILES'ı boşaltır; net mesaj ver.
    $clen = (int)($_SERVER['CONTENT_LENGTH'] ?? 0);
    if ($clen > 0 && empty($_POST) && empty($_FILES)) {
        $errors[] = 'Dosya çok büyük: gönderim PHP post_max_size sınırını aştı. '
            . 'XAMPP php.ini içinde upload_max_filesize ve post_max_size değerlerini artır (örn. 256M) ve Apache\'yi yeniden başlat.';
    } else {
        csrf_check();
        if (empty($_FILES['media']) || $_FILES['media']['error'] === UPLOAD_ERR_NO_FILE) {
            $errors[] = 'Lütfen bir fotoğraf veya video dosyası seç.';
        } else {
            $res = store_media_upload((int)$u['id'], $_FILES['media'], $_POST);
            if ($res['ok']) {
                flash('Medya yüklendi.', 'success');
                redirect('media.php?id=' . $res['id']);
            } else {
                $errors[] = $res['error'];
            }
        }
    }
}

page_header('Yükle');
?>
<div class="card">
    <h1>Gözlem medyası yükle</h1>
    <p class="muted">Teleskopla çekilen fotoğraf/videoyu meta verisiyle arşive ekle.
        Alanların çoğu opsiyoneldir; mobil uygulama bunları otomatik doldurabilir.</p>

    <?php foreach ($errors as $err): ?>
        <div class="alert alert-error"><?= e($err) ?></div>
    <?php endforeach; ?>

    <form method="post" enctype="multipart/form-data" class="grid-form">
        <input type="hidden" name="csrf" value="<?= e(csrf_token()) ?>">

        <label class="full">Dosya (foto veya video)
            <input type="file" name="media" accept="image/*,video/*" required>
            <small class="muted">JPEG/PNG/WEBP veya MP4/WEBM/AVI/MOV. En fazla <?= human_size(MAX_UPLOAD_BYTES) ?>.</small>
        </label>

        <label>Hedef gök cismi
            <input type="text" name="target_name" placeholder="örn. Ay, Jüpiter">
        </label>
        <label>Tür
            <select name="object_type">
                <option value="">— seç —</option>
                <option value="moon">Ay</option>
                <option value="sun">Güneş</option>
                <option value="planet">Gezegen</option>
                <option value="star">Yıldız</option>
                <option value="dso">Derin gök (DSO)</option>
                <option value="other">Diğer</option>
            </select>
        </label>

        <label>Azimut (°)
            <input type="number" step="0.1" name="azimuth" placeholder="0-360">
        </label>
        <label>Yükseklik / Altitude (°)
            <input type="number" step="0.1" name="altitude" placeholder="-90 - 90">
        </label>

        <label>GPS enlem
            <input type="number" step="0.000001" name="gps_lat" placeholder="41.0082">
        </label>
        <label>GPS boylam
            <input type="number" step="0.000001" name="gps_lon" placeholder="28.9784">
        </label>

        <label>Parlaklık (magnitude)
            <input type="number" step="0.1" name="magnitude">
        </label>
        <label>Çekim zamanı
            <input type="datetime-local" name="captured_at">
        </label>

        <label>Video FPS
            <input type="number" step="1" name="fps" placeholder="video ise">
        </label>
        <label>Süre (sn)
            <input type="number" step="0.1" name="duration_sec" placeholder="video ise">
        </label>

        <label class="check full">
            <input type="checkbox" name="ai_verified" value="1">
            Yapay zekâ ile doğrulandı (kadrajda hedef var)
        </label>
        <label>AI güven (0-1)
            <input type="number" step="0.01" min="0" max="1" name="ai_confidence">
        </label>
        <label>AI notu
            <input type="text" name="ai_message" placeholder="model açıklaması">
        </label>

        <label class="full">Oturum kimliği (opsiyonel)
            <input type="text" name="session_id" placeholder="gözlem oturumu id">
        </label>
        <label class="full">Notlar
            <textarea name="notes" rows="3"></textarea>
        </label>

        <div class="full">
            <button class="btn" type="submit">Yükle ve arşive ekle</button>
        </div>
    </form>
</div>
<?php page_footer();
