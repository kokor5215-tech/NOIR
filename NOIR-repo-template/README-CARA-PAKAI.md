# NOIR — Hidup Tanpa Build Ulang (Update Pipeline)

Hasil riset mendalam. Ada TIGA lapisan update; dua pertama sudah
terpasang di app, yang ketiga ilmu tingkat akhir (tidak dipakai,
alasan di bawah).

```
PERUBAHAN DI REPO GITHUB-MU
        |
        |-- provider/extension (.cs3)  --> L1: app update OTOMATIS, 0 build APK
        |
        |-- code inti app              --> L2: GitHub Actions build APK +
        |                                        rilis; app pasang sendiri
        |
        '-- (L3 hot-patch dex ala WeChat: mungkin tapi tidak dipakai)
```

## L1 — Extension tanpa build ulang APK (sudah didukung app)
Cloudstream/Noir memuat provider sebagai file `.cs3` (dex) saat runtime
lewat `DexClassLoader`, jadi source video bisa diganti total tanpa
menyentuh APK. App mengecek `repository.json` + `plugins.json` dan
meng-update plugin otomatis (Setelan → Pembaruan → auto plugin update).

**Langkah:**
1. Buat repo GitHub publik, misal `NOIR-extensions`.
2. Taruh `repository.json` dari folder ini, ganti `USERNAME/NOIR-extensions`.
3. Source extension di folder `extensions/<NamaProvider>/` + workflow
   `.github/workflows/build-extensions.yml` dari folder ini.
4. Ambil URL repo-mu:
   `https://raw.githubusercontent.com/USERNAME/NOIR-extensions/main/repository.json`
5. Buka `app/src/main/java/com/lagradost/cloudstream3/utils/NoirUpdateHub.kt`
   → isi `NOIR_EXTENSIONS_REPO_URL` dengan URL itu (satu-satunya baris
   yang perlu diubah). Build ulang SEKALI ini; setelahnya semua
   perubahan extension masuk sendiri tanpa build apa pun.
   (Alternatif tanpa edit kode: Setelan → Extensions → Add repository,
   tempel URL-nya manual.)

## L2 — APK update sendiri dari GitHub Releases (sudah terpasang)
`InAppUpdater` di app mengecek GitHub Releases
(`BuildConfig.UPDATE_GITHUB_USER/REPO`) tiap app dibuka; bila ada APK
versi lebih baru → dialog update → download → install otomatis.

**Langkah (sekali setup):**
1. Ekstrak Noir-source.zip menjadi repo GitHub (misal `NOIR-app`).
2. Buat kunci signing SEKALI seumur hidup (WAJIB sama terus, kalau
   tidak update tidak bisa menimpa):
   ```
   keytool -genkeypair -keystore noir.keystore -alias noir \
           -keyalg RSA -keysize 2048 -validity 10000
   base64 -w0 noir.keystore   # salin hasilnya
   ```
3. Repo → Settings → Secrets and variables → Actions, isi:
   `NOIR_KEYSTORE_BASE64`, `NOIR_KEYSTORE_PASSWORD`, `NOIR_KEY_ALIAS`,
   `NOIR_KEY_PASSWORD`.
4. Salin `.github/workflows/build-apk.yml` dari folder ini ke repo-mu.
5. Rilis: `git tag v1.4.0 && git push origin v1.4.0`
   → Actions build → APK `Noir-1.4.0.apk` masuk Releases → app di HP
   update sendiri. (Versi di nama file harus X.Y.Z — InAppUpdater
   membacanya dari sana.)

Catatan: APK pertama yang kamu pasang juga harus ditandatangani kunci
yang sama — jadi setelah setup Secrets, build APK pertama lewat
Actions juga (atau ekspor `keystore.jks` yang sama ke Colab).

## L3 — Ilmu gila: hot-patch dex tanpa install (TIDAK dipakai)
Teknologi kelas WeChat/Alipay: **Tencent Tinker**, **Meituan Robust**,
**Alibaba Sophix** — menambal dex/native-lib/resource tanpa reinstall
APK. Secara teori bisa membuat code inti app berubah dari repo tanpa
APK baru. Tidak dipakai karena: butuh pencocokan signature & baseline
APK yang ketat, rapuh antar-ROM Android (terutama Android 14+),
menambah permukaan keamanan (download kode eksekusi = risiko), dan
kompleksitas build berlipat. L1+L2 menutup kebutuhan nyata dengan cara
yang 100% legal, stabil, dan bisa kamu rawat sendiri.
