<?php
require_once __DIR__ . '/lib.php';
$u = require_login();

// --- Filtreler ---
$type = $_GET['type'] ?? '';
$q = trim($_GET['q'] ?? '');
$mine = isset($_GET['mine']);
$onlyVerified = isset($_GET['verified']);

$where = [];
$params = [];
$types = '';

if ($type === 'photo' || $type === 'video') {
    $where[] = 'm.type = ?';
    $params[] = $type;
    $types .= 's';
}
if ($q !== '') {
    $where[] = '(m.target_name LIKE ? OR m.object_type LIKE ?)';
    $params[] = "%$q%";
    $params[] = "%$q%";
    $types .= 'ss';
}
if ($mine) {
    $where[] = 'm.user_id = ?';
    $params[] = (int)$u['id'];
    $types .= 'i';
}
if ($onlyVerified) {
    $where[] = 'm.ai_verified = 1';
}

$sql = 'SELECT m.*, us.username FROM media m JOIN users us ON us.id = m.user_id';
if ($where) {
    $sql .= ' WHERE ' . implode(' AND ', $where);
}
$sql .= ' ORDER BY m.captured_at DESC, m.id DESC LIMIT 200';

$stmt = db()->prepare($sql);
if ($types !== '') {
    $stmt->bind_param($types, ...$params);
}
$stmt->execute();
$rows = $stmt->get_result()->fetch_all(MYSQLI_ASSOC);

$total = (int)(db()->query('SELECT COUNT(*) c FROM media')->fetch_assoc()['c']);

page_header('Galeri');
render_flash();
?>
<div class="page-head">
    <div>
        <h1>Gözlem Galerisi</h1>
        <p class="muted"><?= $total ?> medya arşivde</p>
    </div>
    <a class="btn" href="upload.php">+ Yeni yükle</a>
</div>

<form class="filters" method="get">
    <input type="search" name="q" value="<?= e($q) ?>" placeholder="Hedef ara (Ay, Jüpiter...)">
    <select name="type" onchange="this.form.submit()">
        <option value="">Tümü</option>
        <option value="photo" <?= $type === 'photo' ? 'selected' : '' ?>>Fotoğraf</option>
        <option value="video" <?= $type === 'video' ? 'selected' : '' ?>>Video</option>
    </select>
    <label class="chip"><input type="checkbox" name="mine" <?= $mine ? 'checked' : '' ?> onchange="this.form.submit()"> Sadece benim</label>
    <label class="chip"><input type="checkbox" name="verified" <?= $onlyVerified ? 'checked' : '' ?> onchange="this.form.submit()"> AI doğrulanmış</label>
    <button class="btn-ghost" type="submit">Filtrele</button>
</form>

<?php if (!$rows): ?>
    <div class="empty">
        <p>Henüz medya yok.</p>
        <a class="btn" href="upload.php">İlk gözlemini yükle</a>
    </div>
<?php else: ?>
    <div class="grid">
        <?php foreach ($rows as $m): ?>
            <a class="tile" href="media.php?id=<?= (int)$m['id'] ?>">
                <div class="thumb">
                    <?php if ($m['type'] === 'photo'): ?>
                        <img loading="lazy" src="uploads/<?= e($m['file_name']) ?>" alt="<?= e($m['target_name']) ?>">
                    <?php else: ?>
                        <video muted preload="metadata" src="uploads/<?= e($m['file_name']) ?>#t=0.1"></video>
                        <span class="badge play">▶ video</span>
                    <?php endif; ?>
                    <?php if ($m['ai_verified']): ?>
                        <span class="badge ok">✓ AI</span>
                    <?php endif; ?>
                </div>
                <div class="tile-body">
                    <strong><?= e($m['target_name'] ?: 'İsimsiz') ?></strong>
                    <span class="muted small">
                        Az <?= deg($m['azimuth'] !== null ? (float)$m['azimuth'] : null) ?> ·
                        Alt <?= deg($m['altitude'] !== null ? (float)$m['altitude'] : null) ?>
                    </span>
                    <span class="muted small"><?= e($m['username']) ?> · <?= e(date('d.m.Y H:i', strtotime($m['captured_at']))) ?></span>
                </div>
            </a>
        <?php endforeach; ?>
    </div>
<?php endif; ?>
<?php page_footer();
