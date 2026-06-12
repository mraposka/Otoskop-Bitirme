<#
  Otoskop ESP32-CAM - Bulut OTA yayinlama yardimcisi (GitHub web/Desktop ile)
  ---------------------------------------------------------------------------
  Bu bilgisayarda repo klonu YOK; dosyalari GitHub web sitesinden elle
  yukluyorsun. Bu betik git'e DOKUNMAZ; sadece yuklenecek 2 dosyayi hazirlar:
      ota/firmware.bin   (en yeni derlenen binary)
      ota/version.json   (surum bilgisi)

  Surum numarasi: betik FW_VERSION'i dogrudan .ino kaynagindan okur ve
  version.json'a aynen yazar. Boylece elle senkron tutma derdi yok.
  -> Yeni surum cikmak icin TEK yapman gereken: .ino icinde FW_VERSION'i
     artirip (orn. 1 -> 2) tekrar derlemek (Export Compiled Binary).

  Kullanim (PROJE KOKUNDE, PowerShell):
    ./tools/release.ps1

  Sonra ekrandaki adimla 2 dosyayi GitHub'a yukle. ESP en gec 5 dk icinde
  (veya telefondan GET /ota/check ile hemen) kendini gunceller.
#>

param(
  [string]$Bin = "",
  [string]$Ino = "C:/Users/Admin/Desktop/Otoskop-Bitirme/Firmware/ESP32_Pure_Firmware/esp32cam_otoskop/esp32cam_otoskop.ino",
  [string]$VersionFile = "C:/Users/Admin/Desktop/Otoskop-Bitirme/Firmware/ESP32_Pure_Firmware/ota/version.json",
  [string]$BinDest = "C:/Users/Admin/Desktop/Otoskop-Bitirme/Firmware/ESP32_Pure_Firmware/ota/firmware.bin",
  [string]$BuildDir = "C:/Users/Admin/Desktop/Otoskop-Bitirme/Firmware/ESP32_Pure_Firmware/esp32cam_otoskop/build"
)

$ErrorActionPreference = "Stop"

# Proje kokune gec (bu betik tools/ altinda)
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path $Ino))         { throw ".ino bulunamadi: $Ino" }
if (-not (Test-Path $VersionFile)) { throw "version.json bulunamadi: $VersionFile" }

# 1) FW_VERSION'i kaynaktan oku
$inoText = Get-Content $Ino -Raw
$m = [regex]::Match($inoText, '(?m)^\s*#define\s+FW_VERSION\s+(\d+)')
if (-not $m.Success) { throw "FW_VERSION bulunamadi ($Ino icinde '#define FW_VERSION <sayi>')" }
$fwVersion = [int]$m.Groups[1].Value

# 2) .bin yolu verilmediyse en yeni esp32cam_otoskop.ino.bin'i bul
if ($Bin -eq "") {
  if (-not (Test-Path $BuildDir)) {
    throw "build klasoru yok: $BuildDir`nArduino IDE'de once 'Sketch > Export Compiled Binary' calistir."
  }
  $cand = Get-ChildItem -Path $BuildDir -Recurse -Filter "*.ino.bin" -ErrorAction SilentlyContinue |
          Where-Object { $_.Name -notmatch '\.(bootloader|merged|partitions)\.bin$' } |
          Sort-Object LastWriteTime -Descending |
          Select-Object -First 1
  if ($null -eq $cand) { throw "$BuildDir altinda .ino.bin yok. 'Export Compiled Binary' aldin mi?" }
  $Bin = $cand.FullName
}
if (-not (Test-Path $Bin)) { throw ".bin bulunamadi: $Bin" }

# 2b) GUVENLIK: kaynak .bin'den yeni mi? (FW_VERSION'i degistirip yeniden
#     derlememe hatasini yakalar -> aksi halde sonsuz OTA dongusu olur)
$inoTime = (Get-Item $Ino).LastWriteTime
$binTime = (Get-Item $Bin).LastWriteTime
if ($inoTime -gt $binTime) {
  throw (".ino ({0}) .bin'den ({1}) YENI! FW_VERSION={2} ile TEKRAR derlemelisin " -f $inoTime, $binTime, $fwVersion) +
        "(Arduino IDE > Sketch > Export Compiled Binary), yoksa cihaz sonsuz dongude indirir."
}

# 3) firmware.bin'i hazirla
New-Item -ItemType Directory -Force -Path (Split-Path $BinDest) | Out-Null
Copy-Item -Force $Bin $BinDest
$binAgeMin = [math]::Round(((Get-Date) - (Get-Item $Bin).LastWriteTime).TotalMinutes, 1)

# 4) version.json'i guncelle (url'i koru, version = FW_VERSION)
#    ONEMLI: BOM'suz UTF-8 yaz! Set-Content -Encoding UTF8 (PS 5.1) BOM ekler
#    ve ArduinoJson "{" beklerken parse edemez -> "version.json parse hata".
$json = Get-Content $VersionFile -Raw | ConvertFrom-Json
$oldVer = [int]$json.version
$json.version = $fwVersion
$out = ($json | ConvertTo-Json -Depth 5)
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText((Resolve-Path $VersionFile), $out + "`n", $utf8NoBom)

Write-Host ""
Write-Host "==================== HAZIR ====================" -ForegroundColor Green

