<?php
require_once __DIR__ . '/lib.php';
$u = require_login();

$id = (int)($_GET['id'] ?? 0);
$stmt = db()->prepare('SELECT m.*, us.username FROM media m JOIN users us ON us.id = m.user_id WHERE m.id = ?');
$stmt->bind_param('i', $id);
$stmt->execute();
$m = $stmt->get_result()->fetch_assoc();

if (!$m) {
    http_response_code(404);
    page_header('Bulunamadı');
    echo '<div class="card"><h1>Medya bulunamadı</h1><a class="btn" href="gallery.php">Galeriye dön</a></div>';
    page_footer();
    exit;
}

$canDelete = ((int)$m['user_id'] === (int)$u['id']) || $u['role'] === 'admin';

// Silme
if ($_SERVER['REQUEST_METHOD'] === 'POST' && ($_POST['action'] ?? '') === 'delete') {
    csrf_check();
    if (!$canDelete) {
        http_response_code(403);
        die('Yetkiniz yok.');
    }
    @unlink(__DIR__ . '/uploads/' . $m['file_name']);
    $d = db()->prepare('DELETE FROM media WHERE id = ?');
    $d->bind_param('i', $id);
    $d->execute();
    flash('Medya silindi.', 'success');
    redirect('gallery.php');
}

page_header($m['target_name'] ?: 'Medya');
?>
<a class="back" href="gallery.php">← Galeri</a>
<div class="detail">
    <div class="viewer">
        <?php if ($m['type'] === 'photo'): ?>
            <img src="uploads/<?= e($m['file_name']) ?>" alt="<?= e($m['target_name']) ?>">
        <?php else: ?>
            <video src="uploads/<?= e($m['file_name']) ?>" controls preload="metadata"></video>
        <?php endif; ?>
    </div>
    <aside class="meta">
        <h1><?= e($m['target_name'] ?: 'İsimsiz gözlem') ?></h1>
        <div class="tags">
            <span class="tag"><?= $m['type'] === 'photo' ? 'Fotoğraf' : 'Video' ?></span>
            <?php if ($m['object_type']): ?><span class="tag"><?= e($m['object_type']) ?></span><?php endif; ?>
            <?php if ($m['ai_verified']): ?><span class="tag ok">✓ AI doğrulandı</span><?php endif; ?>
        </div>

        <table class="kv">
            <tr><th>Azimut</th><td><?= deg($m['azimuth'] !== null ? (float)$m['azimuth'] : null) ?></td></tr>
            <tr><th>Yükseklik</th><td><?= deg($m['altitude'] !== null ? (float)$m['altitude'] : null) ?></td></tr>
            <tr><th>GPS</th><td><?= $m['gps_lat'] !== null ? e(number_format((float)$m['gps_lat'], 5)) . ', ' . e(number_format((float)$m['gps_lon'], 5)) : '—' ?></td></tr>
            <tr><th>Parlaklık</th><td><?= $m['magnitude'] !== null ? e($m['magnitude']) : '—' ?></td></tr>
            <?php if ($m['type'] === 'video'): ?>
                <tr><th>FPS</th><td><?= $m['fps'] !== null ? e($m['fps']) : '—' ?></td></tr>
                <tr><th>Süre</th><td><?= $m['duration_sec'] !== null ? e($m['duration_sec']) . ' sn' : '—' ?></td></tr>
            <?php else: ?>
                <tr><th>Çözünürlük</th><td><?= $m['width'] ? e($m['width']) . '×' . e($m['height']) : '—' ?></td></tr>
            <?php endif; ?>
            <tr><th>AI güven</th><td><?= $m['ai_confidence'] !== null ? e(round((float)$m['ai_confidence'] * 100)) . '%' : '—' ?></td></tr>
            <?php if ($m['ai_message']): ?><tr><th>AI notu</th><td><?= e($m['ai_message']) ?></td></tr><?php endif; ?>
            <tr><th>Çekim</th><td><?= e(date('d.m.Y H:i', strtotime($m['captured_at']))) ?></td></tr>
            <tr><th>Yükleyen</th><td><?= e($m['username']) ?></td></tr>
            <tr><th>Boyut</th><td><?= human_size((int)$m['file_size']) ?></td></tr>
            <?php if ($m['session_id']): ?><tr><th>Oturum</th><td><?= e($m['session_id']) ?></td></tr><?php endif; ?>
        </table>

        <?php if ($m['notes']): ?>
            <p class="notes"><?= nl2br(e($m['notes'])) ?></p>
        <?php endif; ?>

        <div class="actions">
            <a class="btn-ghost" href="uploads/<?= e($m['file_name']) ?>" download>İndir</a>
            <?php if ($canDelete): ?>
                <form method="post" onsubmit="return confirm('Bu medyayı silmek istediğine emin misin?');">
                    <input type="hidden" name="csrf" value="<?= e(csrf_token()) ?>">
                    <input type="hidden" name="action" value="delete">
                    <button class="btn-danger" type="submit">Sil</button>
                </form>
            <?php endif; ?>
        </div>
    </aside>
</div>
<?php page_footer();
