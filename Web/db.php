<?php
require_once __DIR__ . '/config.php';

/**
 * Tek bir mysqli bağlantısı döndürür; ilk çağrıda veritabanını ve
 * tabloları (yoksa) otomatik oluşturur. Böylece phpMyAdmin'de elle
 * tablo açmana gerek yok.
 */
function db(): mysqli
{
    static $conn = null;
    if ($conn instanceof mysqli) {
        return $conn;
    }
    mysqli_report(MYSQLI_REPORT_OFF);

    $boot = @new mysqli(DB_HOST, DB_USER, DB_PASS, '', DB_PORT);
    if ($boot->connect_errno) {
        http_response_code(500);
        die('Veritabanına bağlanılamadı: ' . htmlspecialchars($boot->connect_error)
            . ' — XAMPP\'ta MySQL servisi çalışıyor mu?');
    }
    $boot->query('CREATE DATABASE IF NOT EXISTS `' . DB_NAME
        . '` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
    $boot->close();

    $conn = new mysqli(DB_HOST, DB_USER, DB_PASS, DB_NAME, DB_PORT);
    if ($conn->connect_errno) {
        http_response_code(500);
        die('Veritabanı seçilemedi: ' . htmlspecialchars($conn->connect_error));
    }
    $conn->set_charset('utf8mb4');
    migrate($conn);
    return $conn;
}

function migrate(mysqli $c): void
{
    $c->query("CREATE TABLE IF NOT EXISTS users (
        id INT AUTO_INCREMENT PRIMARY KEY,
        username VARCHAR(50) NOT NULL UNIQUE,
        email VARCHAR(190) NOT NULL UNIQUE,
        password_hash VARCHAR(255) NOT NULL,
        role ENUM('user','admin') NOT NULL DEFAULT 'user',
        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

    $c->query("CREATE TABLE IF NOT EXISTS media (
        id INT AUTO_INCREMENT PRIMARY KEY,
        user_id INT NOT NULL,
        type ENUM('photo','video') NOT NULL,
        file_name VARCHAR(255) NOT NULL,
        original_name VARCHAR(255) DEFAULT NULL,
        mime VARCHAR(100) DEFAULT NULL,
        file_size BIGINT DEFAULT 0,
        target_name VARCHAR(120) DEFAULT NULL,
        object_type VARCHAR(40) DEFAULT NULL,
        azimuth DOUBLE DEFAULT NULL,
        altitude DOUBLE DEFAULT NULL,
        gps_lat DOUBLE DEFAULT NULL,
        gps_lon DOUBLE DEFAULT NULL,
        magnitude DOUBLE DEFAULT NULL,
        ai_verified TINYINT(1) DEFAULT 0,
        ai_confidence DOUBLE DEFAULT NULL,
        ai_message VARCHAR(255) DEFAULT NULL,
        width INT DEFAULT NULL,
        height INT DEFAULT NULL,
        fps DOUBLE DEFAULT NULL,
        duration_sec DOUBLE DEFAULT NULL,
        session_id VARCHAR(64) DEFAULT NULL,
        notes TEXT DEFAULT NULL,
        captured_at DATETIME DEFAULT NULL,
        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        INDEX idx_user (user_id),
        INDEX idx_type (type),
        INDEX idx_target (target_name),
        INDEX idx_captured (captured_at),
        CONSTRAINT fk_media_user FOREIGN KEY (user_id)
            REFERENCES users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
}
