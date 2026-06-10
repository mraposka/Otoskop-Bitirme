<?php
/**
 * Otoskop Web - yapılandırma.
 * Sunucuya taşırken yalnızca bu dosyayı düzenlemen yeterli.
 */

// --- Veritabanı (XAMPP varsayılanı: root / boş şifre) ---
const DB_HOST = '127.0.0.1';
const DB_PORT = 3306;
const DB_NAME = 'otoskop';
const DB_USER = 'root';
const DB_PASS = '';

// --- Cihaz/Mobil yükleme API anahtarı (gerçek sunucuda MUTLAKA değiştir) ---
const API_KEY = 'otoskop-dev-key-change-me';

// --- Yükleme kuralları ---
const MAX_UPLOAD_BYTES = 209715200; // 200 MB
const ALLOWED_IMAGE = ['image/jpeg', 'image/png', 'image/webp'];
const ALLOWED_VIDEO = ['video/mp4', 'video/webm', 'video/x-msvideo', 'video/avi', 'video/quicktime'];

const SITE_NAME = 'Otoskop Gözlem Arşivi';

date_default_timezone_set('Europe/Istanbul');

if (session_status() === PHP_SESSION_NONE) {
    session_start();
}
