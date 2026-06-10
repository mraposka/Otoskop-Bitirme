<?php
require_once __DIR__ . '/lib.php';

if (current_user()) {
    redirect('gallery.php');
}

$error = '';
$ident = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    csrf_check();
    $ident = trim($_POST['ident'] ?? '');
    $pass = (string)($_POST['password'] ?? '');

    $stmt = db()->prepare(
        'SELECT id, password_hash FROM users WHERE username = ? OR email = ? LIMIT 1'
    );
    $stmt->bind_param('ss', $ident, $ident);
    $stmt->execute();
    $row = $stmt->get_result()->fetch_assoc();

    if ($row && password_verify($pass, $row['password_hash'])) {
        login_user((int)$row['id']);
        redirect('gallery.php');
    } else {
        $error = 'Kullanıcı adı/e-posta veya şifre hatalı.';
    }
}

page_header('Giriş');
?>
<div class="card auth-card">
    <h1>Giriş yap</h1>
    <p class="muted">Otoskop gözlem arşivine hoş geldin.</p>
    <?php if ($error): ?>
        <div class="alert alert-error"><?= e($error) ?></div>
    <?php endif; ?>
    <form method="post">
        <input type="hidden" name="csrf" value="<?= e(csrf_token()) ?>">
        <label>Kullanıcı adı veya e-posta
            <input type="text" name="ident" value="<?= e($ident) ?>" required autofocus>
        </label>
        <label>Şifre
            <input type="password" name="password" required>
        </label>
        <button class="btn btn-block" type="submit">Giriş yap</button>
    </form>
    <p class="muted center">Hesabın yok mu? <a href="register.php">Kayıt ol</a></p>
</div>
<?php page_footer();
