# Xinema — arsitektur pengembangan

## Tujuan

Xinema adalah repository ekstensi CloudStream. Setiap provider tetap menjadi plugin mandiri (`.cs3`) agar kegagalan atau pembaruan satu situs tidak mematikan provider lain.

## Struktur modul

- Satu direktori tingkat atas untuk setiap provider, misalnya `Oploverz/` dan `Oppadrama/`.
- Setiap provider memiliki `build.gradle.kts`, `AndroidManifest.xml`, kelas `MainAPI`, dan kelas `Plugin` sendiri.
- `settings.gradle.kts` mendaftarkan otomatis setiap direktori provider yang memiliki `build.gradle.kts`.
- Logika extractor khusus provider tetap berada di modul provider sampai ada alasan teknis yang kuat untuk memindahkannya ke modul bersama.

## Batas tanggung jawab

1. Provider: katalog, kategori, pencarian, detail, episode, dan pengumpulan URL player.
2. Extractor: mengubah URL player menjadi `ExtractorLink` dan subtitle.
3. Resolver: normalisasi URL, redirect domain, deduplikasi, dan pemilihan fallback.
4. Repository build: menghasilkan `.cs3`, `plugins.json`, dan `repo.json`.

## Aturan ekstraksi

- `loadLinks()` mengembalikan `true` hanya jika callback benar-benar menghasilkan minimal satu `ExtractorLink`.
- URL iframe/player bukan bukti bahwa stream berhasil ditemukan.
- Pertahankan referer, user-agent, cookie, dan header yang dibutuhkan server.
- Coba setiap mirror secara terisolasi; satu kegagalan tidak boleh menghentikan mirror lain.
- Deduplicate URL stream dan subtitle.
- Jangan menambahkan extractor generik yang mengklaim sukses tanpa menghasilkan stream.
- Tambahkan extractor spesifik setelah pola server dan format responsnya terverifikasi.

## Build dan publikasi

GitHub Actions membangun semua modul provider dan memvalidasi bahwa setiap paket `.cs3` tercantum di `plugins.json`. Branch `foundation-rebuild` hanya menjalankan validasi build; publikasi ke branch `builds` hanya dilakukan dari `main`.

## Referensi pola implementasi

- `ExtremeBoyGG/nonton-indo`: pola multi-modul, konfigurasi Gradle bersama, manifest repository, dan workflow build.
- `Asm0d3usX/CloudX-V2`: pemisahan extractor dari provider serta penanganan AJAX, mirror, HLS, dan packed JavaScript.
- `bikinveo-hash/RepairPremium_Repo`: extractor khusus HydraX dan penanganan stream terenkripsi/proxy. Implementasi proxy hanya boleh dipindahkan setelah kebutuhan dan protokol sumber dipastikan.

Referensi tersebut menjadi pola desain, bukan kode yang disalin membabi buta. Setiap implementasi harus disesuaikan dengan struktur situs dan API CloudStream yang dipakai Xinema.
