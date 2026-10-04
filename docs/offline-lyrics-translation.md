# Terjemahan lirik AI luring

## Privasi dan unduhan

- Terjemahan menggunakan Tencent **Hy-MT2-1.8B Q4_K_M** melalui llama.cpp yang berjalan di perangkat.
- Tidak ada permintaan terjemahan yang dikirim ke Google Translate, MyMemory, atau layanan cloud lain. Pengambilan lirik dari penyedia lirik tetap memakai alur jaringan yang sudah ada.
- Bobot model **tidak disertakan di APK**. Saat pengguna mengaktifkan terjemahan pertama kali, aplikasi meminta persetujuan untuk mengunduh sekitar **1,13 GB** dari Hugging Face.
- File disimpan di penyimpanan privat `noBackupFilesDir/noir_models`. Unduhan parsial (`.part`) dapat dilanjutkan dengan HTTP Range, lalu SHA-256 diperiksa sebelum file dipasang.
- Lihat `app/src/main/assets/NOIR_AI_NOTICES.txt` dan file lisensi yang bersebelahan untuk atribusi lengkap.

## Model yang dipatok

- Repo: `tencent/Hy-MT2-1.8B-GGUF`
- Revision: `a0c709d9fac510f2c807aa3af52872340dc37a4a`
- File: `Hy-MT2-1.8B-Q4_K_M.gguf`
- Ukuran: `1,133,080,448` byte
- SHA-256: `dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699`
- Runtime: llama.cpp v0.5.0, CPU-only, arsip sumber dipatok dengan SHA-256 di `app/src/main/cpp/CMakeLists.txt`.

## Dukungan perangkat dan build

- Minimum Android tetap API 23; inferensi hanya tersedia saat proses aplikasi 64-bit (`arm64-v8a` atau `x86_64`).
- Jalur AI memeriksa RAM dan penyimpanan kosong, menampilkan progres, mendukung pembatalan, serta melepas model setelah masa idle.
- Build membutuhkan NDK `27.2.12479018` dan CMake `3.22.1`; keduanya dipasang oleh notebook Colab dan workflow GitHub Actions.
- Jangan menambahkan bobot `.gguf` ke source ZIP/APK tanpa persetujuan dan tinjauan lisensi terpisah.

## Batasan dan status validasi

- Pemutar saat ini meminta terjemahan ke Bahasa Indonesia. Romanisasi hanya tersedia bila sumber lirik menyertakan sidecar; tidak ada fallback transliterasi online.
- Kode JNI sudah diperiksa sintaksnya dengan `g++ -fsyntax-only -Wall -Wextra -Werror` terhadap header llama.cpp v0.5.0, dan checksum model/release sudah dicocokkan dengan metadata upstream.
- **Build CMake Android/APK serta inferensi di perangkat belum dijalankan di sandbox ini.** Jalankan notebook Colab, lalu kirim APK/log atau screenshot hasil uji untuk validasi runtime dan kualitas terjemahan.
