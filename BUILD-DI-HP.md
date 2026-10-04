# NOIR — Cara Mengubah Kode Ini Jadi APK

> Jawaban singkat untuk pertanyaanmu:
> **Kompilasi di HP itu tidak mungkin. Tapi mendapatkan APK-nya dari HP itu bisa.**
> Bedanya penting, dan dijelaskan di bagian 1.

---

## 1. Kenapa tidak bisa kompilasi di HP

Ini bukan soal "belum dicoba". Ini angka yang terukur:

| Yang dibutuhkan proyek | HP pada umumnya |
|---|---|
| JDK 17 | tidak ada |
| Android SDK 37 (~2 GB) | tidak ada |
| Heap Gradle **4 GB** (`org.gradle.jvmargs=-Xmx4G`) | RAM HP 4–8 GB, tapi tidak bisa dialokasikan utuh ke satu proses |
| Ruang build ~10 GB | biasanya tidak cukup |
| Kompilasi 234 file Kotlin | sangat lambat |

Termux bisa memasang JDK, tapi **tidak** untuk proyek sebesar ini. Jangan buang waktu di sana.

**Yang bisa dilakukan dari HP:** memicu build di komputer cloud gratis, lalu mengunduh APK hasilnya. Semua lewat browser.

Ada dua tempat cloud gratis yang sanggup, dan **Colab yang paling gampang**:

| | Google Colab | GitHub Actions |
|---|---|---|
| RAM | ~12–13 GB | 16 GB |
| Perlu akun GitHub? | **Tidak** | Ya |
| Perlu upload kode ke GitHub? | **Tidak** (cukup unggah zip) | Ya (fork) |
| Cara | Upload 1 file `.ipynb`, klik Run all | Fork repo, jalankan workflow |
| Waktu | 30–55 menit (termasuk kompilasi native) | 20–40 menit |

**Pakai Colab** kalau kamu tidak mau repot dengan GitHub → lihat **bagian 2A**.
Pakai GitHub kalau kamu memang mau punya repo sendiri → lihat **bagian 2B**.

---

## 2A. Jalur paling gampang: Google Colab

Kamu hanya butuh dua file dari saya:
- **`NOIR-Build-APK.ipynb`** — notebook-nya
- **`noir-source-fix19.zip`** — seluruh kode yang sudah berisi semua perubahan

### Langkah
1. Buka **`colab.research.google.com`**, login akun Google.
2. **File → Upload notebook** → pilih `NOIR-Build-APK.ipynb`.
3. **Runtime → Change runtime type** → pastikan **CPU** (jangan GPU), lalu Save.
4. **Runtime → Run all**.
5. Di sel "Ambil kode sumber", pemilih berkas akan muncul → pilih `noir-source-fix19.zip`.
6. Tunggu 20–35 menit. Sel terakhir otomatis mengunduh `Noir.apk`.

Notebook itu sudah berisi semua perintah yang benar — termasuk versi SDK yang
**sudah saya verifikasi ada di repo Google**, bukan tebakan:

| Kebutuhan proyek | Paket yang dipasang |
|---|---|
| `compileSdk = 37` | `platforms;android-37.0` |
| `targetSdk = 36` | `platforms;android-36` |
| AGP 9.1.1 | `build-tools;37.0.0` |
| Gradle | 9.4.1 (diunduh otomatis oleh wrapper) |
| Android NDK | `27.2.12479018` |
| CMake | `3.22.1` |
| JDK | 17 (`jdkToolchain = 17`) |

Dua jebakan yang sudah ditangani di dalam notebook:
- Zip command-line tools berisi folder `cmdline-tools/`, tapi `sdkmanager` harus berada di `cmdline-tools/latest/`. Notebook melakukan `mv` itu.
- Paket platform bernama `android-37.0`, **bukan** `android-37`. Saya periksa langsung dari arsip `platform-37.0_r02.zip`: foldernya `android-37.0`, dan `source.properties`-nya berisi `AndroidVersion.ApiLevel=37.0` + `IsBaseSdk=true` — itulah yang dicocokkan AGP.
- NDK dan CMake dipasang untuk mengompilasi runtime AI CPU. Bobot model Hy-MT2 **tidak** diunduh saat build dan **tidak** masuk APK. Saat pertama kali pengguna mengaktifkan terjemahan lirik, aplikasi meminta persetujuan sebelum mengunduh model sekitar 1,13 GB. Model bekerja lokal pada Android 64-bit; teks lirik tidak dikirim ke layanan penerjemah.

---

## 2B. Jalur alternatif: GitHub Codespaces

Kamu butuh: akun GitHub + file patch `NOIR-perubahan.patch` (sudah saya siapkan).

### Langkah 1 — Fork repo aslinya
1. Buka `https://github.com/michat88/Noir` di browser HP.
2. Tap **Fork** → **Create fork**.
3. Sekarang kamu punya salinan di `github.com/USERNAME-KAMU/Noir`.

> Repo sudah bernama `Noir` — tidak perlu rename apa pun.

### Langkah 2 — Buka Codespaces
1. Di halaman fork kamu, tap **Code** (tombol hijau).
2. Pilih tab **Codespaces**.
3. Tap **Create codespace on main** / **master**.
4. Tunggu ~1 menit. VS Code akan terbuka di browser, lengkap dengan terminal di bawah.

> Codespaces gratis 60 jam/bulan untuk akun pribadi. Lebih dari cukup.

### Langkah 3 — Masukkan file patch
1. Di panel **Explorer** (kiri), klik kanan pada folder paling atas.
2. Pilih **Upload...**
3. Pilih file `NOIR-perubahan.patch` dari HP kamu.

### Langkah 4 — Terapkan & dorong
Buka terminal (**Terminal → New Terminal**), lalu jalankan baris ini satu per satu:

```bash
git apply NOIR-perubahan.patch
git add -A
git config user.email "kamu@example.com"
git config user.name  "NamaKamu"
git commit -m "NOIR: rebrand + tema monokrom + repo publik"
git push
```

Kalau `git apply` mengeluh, jalankan ini dulu untuk lihat penyebabnya:
```bash
git apply --check -v NOIR-perubahan.patch
```
Patch ini **sudah saya uji apply bersih** di commit `0864e97` (HEAD repo asli saat ini). Jadi kalau fork kamu masih di commit itu, pasti masuk.

### Langkah 5 — Biarkan Actions membangun APK
1. Tap tab **Actions** di GitHub.
2. Kalau muncul peringatan "workflows aren't being run", tap **I understand my workflows, go ahead and enable them**.
3. Pilih workflow **Build APK** → **Run workflow** → **Run workflow**.
4. Tunggu **10–20 menit**.
5. Buka run yang sudah selesai → bagian **Artifacts** di paling bawah → tap **Noir-Debug-APK**.
6. Kamu dapat file `.zip`. Ekstrak → di dalamnya ada `.apk`.

### Langkah 6 — Pasang di HP
1. Buka file `.apk`.
2. Izinkan **Install unknown apps** untuk browser/file manager kamu.
3. Install.

Selesai. Aplikasi terpasang dengan nama **Noir**, paket `com.noir.stream.debug`.

---

## 3. Kalau punya PC (lebih gampang)

Butuh: JDK 17, Android Studio (atau Android SDK + `ANDROID_HOME`), RAM minimal 8 GB.

```bash
# 1. Ambil kode
git clone https://github.com/michat88/Noir.git Noir
cd Noir

# 2. Terapkan perubahan
git apply /path/ke/NOIR-perubahan.patch

# 3. Isi identitas update (opsional tapi disarankan)
cat >> local.properties <<EOF
UPDATE_GITHUB_USER=username-kamu
UPDATE_GITHUB_REPO=nama-repo-kamu
EOF

# 4. Build
./gradlew assembleStableDebug        # APK debug, langsung bisa di-install
# atau
./gradlew assembleStableRelease      # butuh keystore, lihat bagian 5
```

Hasilnya di:
```
app/build/outputs/apk/stable/debug/app-stable-debug.apk
```

Alternatif tanpa CLI: buka foldernya di **Android Studio** → tunggu Gradle sync → menu **Build → Build Bundle(s)/APK(s) → Build APK(s)**.

Atau pakai `noir-source-fix19.zip` yang saya siapkan — itu seluruh proyek yang **sudah** berisi semua perubahan, tinggal ekstrak dan build. Tidak perlu patch.

---

## 4. Dua hal yang wajib kamu ganti

### a) Sumber update in-app
Aplikasi mengecek update ke repo GitHub. Default-nya placeholder.

**Cara gampang (otomatis):** workflow GitHub Actions sudah saya set memakai
`${{ github.repository_owner }}` dan `${{ github.event.repository.name }}`,
jadi kalau kamu build lewat Actions, ini **terisi sendiri**. Tidak perlu ngapa-ngapain.

**Cara manual (build lokal):** isi di `local.properties` seperti di bagian 3.

### b) `pathPrefix` di AndroidManifest
Baris ini masih placeholder:
```xml
android:pathPrefix="/USERNAME_KAMU/REPO_KAMU"
```
Ganti dengan path repo GitHub kamu, misalnya `/budi/Noir`.
Ini hanya dipakai supaya link GitHub repo kamu bisa dibuka langsung dari aplikasi.
**Tidak mengubah fungsi inti** kalau dibiarkan.

---

## 5. APK Release (signed) — opsional

APK debug sudah cukup untuk dipakai sendiri. Release signed perlu kalau kamu mau
distribusi luas (ukuran lebih kecil, tidak ada label "debuggable").

1. Buat keystore di PC:
   ```bash
   keytool -genkey -v -keystore noir.keystore -alias noir \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Ubah ke base64:
   ```bash
   base64 -w 0 noir.keystore > noir.keystore.b64
   ```
3. Di GitHub fork kamu: **Settings → Secrets and variables → Actions**.
   Tambahkan **4 Repository secrets**:
   | Nama | Isi |
   |---|---|
   | `SIGNING_KEY` | isi file `noir.keystore.b64` |
   | `ALIAS` | `noir` |
   | `KEY_STORE_PASSWORD` | password keystore |
   | `KEY_PASSWORD` | password key |
4. Tambahkan **Repository variable** (tab **Variables**):
   | Nama | Isi |
   |---|---|
   | `HAS_SIGNING_KEY` | `true` |
5. Jalankan lagi workflow → job **APK Release** akan ikut jalan.

> Password keystore fallback milik fork lama (`161105`) **sudah saya hapus** dari
> `build.gradle.kts` karena bocor di repo publik. Sekarang wajib diisi sendiri.

---

## 6. Yang sudah saya ubah (changelog)

### Identitas
- Nama aplikasi: **Noir**
- `applicationId`: **`com.noir.stream`**
- `versionName` `4.8.3` → `1.0.0`, `versionCode` `90` → `1`
- Deep-link scheme: **`noir*`** (`noirshare`, `noirsearch`, `noirplayer`, `noirrepo`, `noircontinuewatching`)
- Drawable: **`noir_banner.png`**
- 129 penggantian di 22 file

> ⚠️ `applicationId` **`com.noir.stream`** = aplikasi terpisah dari fork lama.
> Tidak bisa install menimpa aplikasi lama; uninstall dulu yang lama.

### Tema monokrom
`colors.xml` ditulis ulang memakai palet **iOS Dark Mode** asli Apple:
- Latar `#000000` murni (hemat daya di OLED)
- Permukaan `#1C1C1E` / `#141416` / `#0A0A0A`
- Teks `#F5F5F7`, sekunder `#8E8E93` (systemGray)
- Aksen `#F2F2F7` (putih) — **bukan biru lagi**
- `colorOnPrimary` diubah ke hitam supaya teks di atas tombol putih tetap terbaca
- Badge Dub/Sub/Type dibedakan lewat **tingkat abu**, bukan warna

Yang **sengaja tidak** dibuat abu-abu: `adultColor` (penanda NSFW) — tetap merah
teredam `#B4463F` demi keamanan. Dan pemilih warna aksen di Pengaturan masih ada
sebagai opsi, cuma default-nya sekarang monokrom.

### Repo extension — dari 1 provider jadi 3 repo publik
Blok `MainActivity` yang dulu **menghapus paksa** semua repo non-resmi setiap
startup sudah dibuang. Gantinya: **seeding sekali**, tanpa pemaksaan.

```
https://raw.githubusercontent.com/Asm0d3usX/CloudX-V2/builds/repo.json      (18 provider)
https://raw.githubusercontent.com/ExtremeBoyGG/nonton-indo/main/repo.json   (13 provider)
https://raw.githubusercontent.com/recloudstream/repo/master/repo.json       (repo resmi)
```

Setelah ini user bebas menambah/menghapus repo sendiri dan tidak akan dihapus otomatis.

### Sistem premium — dibuang
Karena kamu minta ketiga repo publik terpasang, dan repo-repo itu dulunya justru
dikunci di balik premium, keduanya tidak bisa hidup barengan. Yang dihapus:
- `PremiumManager.kt`, `PremiumDialogManager.kt`, `RepoProtector.kt` (3 file)
- 4 gerbang premium di `MainActivity` (nav menu ×2, deep-link share, migrasi)
- Status langganan + tombol klaim kode promo di Pengaturan
- Blok XOR/`OBFUSCATED_KEY` di `build.gradle.kts` + dependency `security-crypto`
- Auto-redirect di `ExtensionsFragment` yang menghalangi pengelolaan repo

**Aplikasi sekarang 100% gratis.** Tidak ada dialog bayar, tidak ada Firebase,
tidak ada QRIS, tidak ada Telegram admin.

### Infrastruktur milik fork lama — dilepas
- URL shortener Cloudflare Worker lama → fitur share ditulis ulang agar membangun deep link **tanpa panggilan jaringan apa pun**
- Gambar QRIS & banner dari repo `michat88/Zaneta` → dihapus
- Tautan donasi Saweria → diarahkan ke repo CloudStream
- CMS popup kampanye → dinonaktifkan (URL dikosongkan + guard early-return)
- Sumber update in-app → jadi BuildConfig yang bisa dikonfigurasi

### Intro pembuka — ditulis ulang total
Intro lama berupa **file video MP4** (ala Netflix "ta-dum"). Sudah dibuang,
diganti intro yang **digambar dari kode**:

- Latar hitam pekat, satu warna aksen: putih. Tidak ada warna lain.
- Wordmark **NOIR** dalam Product Sans Light; empat hurufnya muncul satu per satu,
  naik perlahan dari bawah.
- Jarak antar huruf melebar pelan (letter-spacing 0.02 → 0.46) selama 1,5 detik.
- Garis rambut setebal 1 px melebar dari tengah, opacity hanya 0.55.
- Tagline `S T R E A M I N G` menyusul dengan systemGray.
- Semua memudar, lalu pindah. Total ±2,6 detik.
- **Satu sentuhan di mana saja = langsung lanjut.** Intro tidak boleh menahan orang.

**Audionya disintesis saat runtime**, bukan file `.mp3`:
- Dua lonceng berjarak *perfect fifth* (A4 440 Hz → E5 659 Hz), nada kedua masuk
  0,34 detik setelah nada pertama, saat yang pertama masih bergema.
- Lima parsial dengan rasio lonceng gereja (1.00 / 2.00 / 2.40 / 3.01 / 4.52),
  masing-masing luruh dengan kecepatan sendiri — parsial tinggi luruh lebih cepat,
  itu yang membuat suaranya terasa seperti logam, bukan peluit.
- Attack 8 ms, gain utama 0.16, fade-out global 220 ms di ujung.
- Mengikuti volume media HP; kalau media di-nol-kan, tidak berbunyi.
- Dirender di thread latar; thread utama tidak pernah diblokir.

Alasannya bukan cuma estetika: tanpa file audio/video, **tidak ada lisensi aset
yang perlu diurus** dan ukuran APK turun ±305 KB (dua video intro dihapus).

### Bersih dari istilah "langganan"
Fitur **notifikasi episode** bawaan CloudStream tetap ada (itu gratis dan berguna),
tapi istilahnya diganti supaya tidak ada yang mengira itu berbayar:

| Kunci | Sebelum | Sesudah |
|---|---|---|
| `subscription_list_name` | Berlangganan | **Diikuti** |
| `subscription_new` | Berlangganan ke %s | **Mengikuti %s** |
| `subscription_deleted` | Berhenti berlangganan di %s | **Berhenti mengikuti %s** |
| `action_subscribe` | Subscribe | **Ikuti** |
| `action_unsubscribe` | Unsubscribe | **Berhenti Ikuti** |
| `subscription_in_progress_notification` | Memperbarui acara langganan | **Memperbarui acara yang diikuti** |

Versi Inggris ikut disesuaikan (Following / Follow / Unfollow).

### Yang dipertahankan (kewajiban GPLv3)
Dialog "Tentang" sekarang menyebut **Noir adalah fork dari CloudStream (GPLv3)
karya Lagradost dan tim**, dan bahwa pemberitahuan hak cipta upstream wajib
dipertahankan. Ini bukan basa-basi — Pasal 5 GPLv3 memang mewajibkannya.

`DeviceIdentity.kt` dipertahankan sebagai utilitas kecil (ID diagnostik 8 digit),
sudah tidak terhubung ke sistem lisensi apa pun.

---

## 7. Yang BELUM saya kerjakan

Jujur, supaya kamu tidak kaget:

| Item | Status | Alasan |
|---|---|---|
| **Glassmorphism / latar transparan tembus pandang** | ❌ belum | Butuh kode View/RenderEffect baru. Tidak bisa saya verifikasi tanpa kompilasi, jadi saya tidak mau mengirim kode yang belum terbukti jalan. |
| **Animasi dinamis di belakang latar** | ❌ belum | Sama. Perlu diuji di perangkat nyata. |
| **Logo & ikon launcher baru** | ❌ belum | Masih pakai ikon lama. Perlu aset gambar baru. |
| **Sembunyikan provider tertentu** | ❌ belum | CloudStream sudah punya *pin/urut* provider (long-press di pemilih beranda), tapi **belum ada** tombol sembunyikan. Menambahnya menyentuh `HomeFragment`, `SearchFragment`, dan `DataStoreHelper` sekaligus — saya mau itu diuji build dulu sebelum dikirim. |
| **"Fitur canggih" lainnya** | ❌ belum | Ini belum berupa spesifikasi. Sebutkan 1–2 yang paling kamu butuhkan, saya kerjakan satu per satu sampai benar. |
| **Kompilasi** | ❌ tidak bisa di sini | Sandbox: RAM 1,9 GB vs kebutuhan heap 4 GB. Lihat bagian 1. |

**Yang sudah saya verifikasi:**
- 554 file XML → semuanya well-formed
- 234 file Kotlin → keseimbangan kurung diperiksa dengan lexer (1 false positive di `ResultFragment.kt`, file itu **tidak saya sentuh** dan selisihnya identik dengan versi asli di git)
- Tidak ada satu pun sisa referensi ke simbol yang sudah dihapus
- Semua warna yang direferensikan masih terdefinisi
- Patch apply bersih (`git apply --check` → exit 0) di HEAD repo asli

**Yang belum terverifikasi:** kompilasi Kotlin sesungguhnya. Itu baru terjadi saat
kamu menjalankan workflow di bagian 2. Kalau ada error, kirimkan log-nya ke saya —
itu justru bagian yang paling berguna.

---

## 8. Urutan yang saya sarankan

1. Jalankan bagian 2 dulu, **tanpa mengubah apa pun lagi**.
2. Kalau build **sukses** → kamu punya APK Noir yang jalan, tema monokrom, 3 repo terpasang. Itu fondasi yang nyata.
3. Baru setelah itu kita tambah glassmorphism + animasi + logo, satu lapis per satu, dan tiap lapis diuji lewat build yang sama.

Menumpuk semua perubahan sekaligus lalu baru build = kalau gagal, kamu tidak tahu
mana penyebabnya. Satu lapis satu build jauh lebih cepat sampai selesai.
