# Otoskop Gözlem Arşivi (Web)

Teleskopla çekilen fotoğraf/videoların kullanıcı hesaplarıyla, zengin meta
veriyle saklanıp listelendiği PHP + MySQL web uygulaması. XAMPP üzerinde
çalışır; sunucuya taşırken yalnızca `config.php` düzenlenir.

## Çalıştırma (XAMPP)

1. XAMPP'ta **Apache** ve **MySQL** servislerini başlat.
2. Bu klasör `C:\xampp\htdocs\otoskop` altında olmalı.
3. Tarayıcıdan aç: **http://localhost/otoskop/**
   - İlk açılışta veritabanı (`otoskop`) ve tablolar otomatik oluşturulur.
   - Elle phpMyAdmin'de tablo açmana gerek yok.
4. İlk kayıt olan kullanıcı otomatik **admin** olur.

## Sayfalar

- `register.php` / `login.php` — hesap oluştur / giriş
- `gallery.php` — galeri + filtre (tür, hedef arama, sadece benim, AI doğrulanmış)
- `upload.php` — dosya seç + meta veri formu ile yükleme
- `media.php?id=…` — detay (büyük görüntü/oynatıcı + tüm meta veri + indir/sil)

## Büyük video yüklemeleri

Varsayılan XAMPP `php.ini` küçük limitlerle gelir. Büyük video için
`C:\xampp\php\php.ini` içinde şunları artır ve Apache'yi yeniden başlat:

```
upload_max_filesize = 256M
post_max_size = 256M
max_execution_time = 300
```

## Cihaz / Mobil yükleme API'si

İleride mobil uygulama otomatik yükleme yapabilsin diye hazır:

`POST http://<host>/otoskop/api/upload.php` (multipart/form-data)

| Alan | Açıklama |
|------|----------|
| `api_key` | `config.php` içindeki `API_KEY` |
| `username` | medyayı sahiplenecek kayıtlı kullanıcı |
| `media` | dosya (foto/video) |
| meta | `target_name, object_type, azimuth, altitude, gps_lat, gps_lon, magnitude, ai_verified, ai_confidence, ai_message, fps, duration_sec, session_id, notes, captured_at` |

Yanıt: `{ "ok": true, "id": 12, "file_name": "...", "url": "http://.../uploads/..." }`

## Güvenlik notları (sunucuya taşırken)

- `config.php` içindeki `API_KEY`'i değiştir, DB kullanıcı/şifresini ayarla.
- `uploads/.htaccess` yüklenen klasörde script çalışmasını engeller (Apache).
- Formlar CSRF korumalı; şifreler `password_hash` ile saklanır; tüm sorgular
  hazır ifade (prepared statement) kullanır.
