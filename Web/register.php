<?php
require_once __DIR__ . '/lib.php';

if (current_user()) {
    redirect('gallery.php');
}

$errors = [];
$username = $email = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    csrf_check();
    $username = trim($_POST['username'] ?? '');
    $email = trim($_POST['email'] ?? '');
    $pass = (string)($_POST['password'] ?? '');
    $pass2 = (string)($_POST['password2'] ?? '');

    if (!preg_match('/^[a-zA-Z0-9_.]{3,50}$/', $username)) {
        $errors[] = 'Kullanıcı adı 3-50 karakter olmalı (harf, rakam, _ . ).';
    }
    if (!filter_var($email, FILTER_VALIDATE_EMAIL)) {
        $errors[] = 'Geçerli bir e-posta girin.';
    }
    if (strlen($pass) < 6) {
        $errors[] = 'Şifre en az 6 karakter olmalı.';
    }
    if ($pass !== $pass2) {
        $errors[] = 'Şifreler eşleşmiyor.';
    }

    if (!$errors) {
        // İlk kullanıcı admin olsun
        $count = (int)(db()->query('SELECT COUNT(*) c FROM users')->fetch_assoc()['c']);
        $role = $count === 0 ? 'admin' : 'user';
        $hash = password_hash($pass, PASSWORD_DEFAULT);
        $stmt = db()->prepare('INSERT INTO users (username, email, password_hash, role) VALUES (?,?,?,?)');
        $stmt->bind_param('ssss', $username, $email, $hash, $role);
        if ($stmt->execute()) {
            login_user($stmt->insert_id);
            flash('Hoş geldin, ' . $username . '!', 'success');
            redirect('gallery.php');
        } else {
            $errors[] = ($stmt->errno === 1062)
                ? 'Bu kullanıcı adı veya e-posta zaten kayıtlı.'
                : 'Kayıt hatası: ' . $stmt->error;
        }
    }
}

page_header('Kayıt ol');
?>
<div class="card auth-card">
    <h1>Kayıt ol</h1>
    <p class="muted">Gözlem arşivine erişmek için bir hesap oluştur.</p>
    <?php foreach ($errors as $err): ?>
        <div class="alert alert-error"><?= e($err) ?></div>
    <?php endforeach; ?>
    <form method="post" autocomplete="off">
        <input type="hidden" name="csrf" value="<?= e(csrf_token()) ?>">
        <label>Kullanıcı adı
            <input type="text" name="username" value="<?= e($username) ?>" required>
        </label>
        <label>E-posta
            <input type="email" name="email" value="<?= e($email) ?>" required>
        </label>
        <label>Şifre
            <input type="password" name="password" required minlength="6">
        </label>
        <label>Şifre (tekrar)
            <input type="password" name="password2" required minlength="6">
        </label>
        <button class="btn btn-block" type="submit">Hesap oluştur</button>
    </form>
    <p class="muted center">Zaten hesabın var mı? <a href="login.php">Giriş yap</a></p>
</div>
<?php page_footer();
