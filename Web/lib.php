<?php
require_once __DIR__ . '/db.php';

/* ----------------------------- Yardımcılar ----------------------------- */

function e(?string $s): string
{
    return htmlspecialchars((string)$s, ENT_QUOTES, 'UTF-8');
}

function redirect(string $path): void
{
    header('Location: ' . $path);
    exit;
}

function csrf_token(): string
{
    if (empty($_SESSION['csrf'])) {
        $_SESSION['csrf'] = bin2hex(random_bytes(32));
    }
    return $_SESSION['csrf'];
}

function csrf_check(): void
{
    $t = $_POST['csrf'] ?? '';
    if (!is_string($t) || !hash_equals($_SESSION['csrf'] ?? '', $t)) {
        http_response_code(400);
        die('Geçersiz oturum (CSRF). Sayfayı yenileyip tekrar deneyin.');
    }
}

/* ------------------------------- Oturum -------------------------------- */

function current_user(): ?array
{
    if (empty($_SESSION['uid'])) {
        return null;
    }
    static $cache = null;
    if ($cache !== null) {
        return $cache;
    }
    $stmt = db()->prepare('SELECT id, username, email, role, created_at FROM users WHERE id = ?');
    $stmt->bind_param('i', $_SESSION['uid']);
    $stmt->execute();
    $cache = $stmt->get_result()->fetch_assoc() ?: null;
    return $cache;
}

function require_login(): array
{
    $u = current_user();
    if (!$u) {
        redirect('login.php');
    }
    return $u;
}

function login_user(int $id): void
{
    session_regenerate_id(true);
    $_SESSION['uid'] = $id;
}

function logout_user(): void
{
    $_SESSION = [];
    session_destroy();
}

/* ------------------------------ Biçimleme ------------------------------ */

function human_size(int $bytes): string
{
    if ($bytes <= 0) return '0 B';
    $u = ['B', 'KB', 'MB', 'GB'];
    $i = (int)floor(log($bytes, 1024));
    $i = min($i, count($u) - 1);
    return round($bytes / (1024 ** $i), 1) . ' ' . $u[$i];
}

function deg(?float $v): string
{
    return $v === null ? '—' : number_format($v, 1, '.', '') . '°';
}

/* ------------------------------- Layout -------------------------------- */

function page_header(string $title): void
{
    $u = current_user();
    ?>
<!DOCTYPE html>
<html lang="tr">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title><?= e($title) ?> · <?= e(SITE_NAME) ?></title>
    <link rel="stylesheet" href="assets/style.css">
</head>
<body>
<header class="topbar">
    <a class="brand" href="gallery.php">
        <span class="logo">◐</span> <?= e(SITE_NAME) ?>
    </a>
    <nav>
        <?php if ($u): ?>
            <a href="gallery.php">Galeri</a>
            <a href="upload.php">Yükle</a>
            <span class="who"><?= e($u['username']) ?></span>
            <a class="btn-ghost" href="logout.php">Çıkış</a>
        <?php else: ?>
            <a href="login.php">Giriş</a>
            <a class="btn" href="register.php">Kayıt ol</a>
        <?php endif; ?>
    </nav>
</header>
<main class="container">
    <?php
}

function page_footer(): void
{
    ?>
</main>
<footer class="foot">
    <span><?= e(SITE_NAME) ?> — yapay zekâ destekli gök cismi gözlem arşivi</span>
</footer>
</body>
</html>
    <?php
}

function flash(string $msg, string $kind = 'info'): void
{
    $_SESSION['flash'] = ['msg' => $msg, 'kind' => $kind];
}

function render_flash(): void
{
    if (empty($_SESSION['flash'])) return;
    $f = $_SESSION['flash'];
    unset($_SESSION['flash']);
    echo '<div class="alert alert-' . e($f['kind']) . '">' . e($f['msg']) . '</div>';
}
