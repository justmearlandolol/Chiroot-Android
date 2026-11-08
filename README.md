# Chiroot Android

Pemutar musik **native Java** untuk Android 10+ (API 29), dengan tampilan kertas/gramofon Chiroot, vinyl yang mengikuti playback nyata, Media3/ExoPlayer, dan Supabase. Ini bukan WebView atau hasil konversi website menjadi APK.

**Ikon APK menggunakan `favicon.png` yang diberikan**, bukan ikon Android bawaan. File sumber dipertahankan; resource legacy dan adaptive icon dibuat darinya. Ilustrasi koneksi/error juga menggunakan gambar asli yang diberikan, tanpa mengubah tulisan di dalamnya.

## Download APK dari GitHub Actions

1. Buka repo → **Actions** → **Build Chiroot APK** → run terbaru yang berhasil.
2. Di bagian **Artifacts**, unduh **`chiroot-debug-apk`**.
3. Ekstrak ZIP artifact, lalu pasang `app-debug.apk` di Android 10 atau lebih baru. Android mungkin meminta izin memasang aplikasi dari sumber tersebut.
4. Artifact adalah hasil build commit yang tercatat di ringkasan run. Jangan memakai APK lama dari commit berbeda.

Workflow dapat dijalankan manual lewat **Run workflow**, serta otomatis pada push ke `main`/branch kerja Arena dan PR ke `main`. Ia menjalankan tes JVM, lint Android, build debug, memeriksa ikon di dalam APK, lalu mengunggah APK dan laporan. Build debug **tidak memerlukan GitHub Secrets**: konfigurasi publik Chiroot menjadi fallback. APK debug memakai ID `id.my.chiroot.debug`, sehingga bisa berdampingan dengan aplikasi release.

> Build/CI berhasil bukan berarti seluruh skenario OAuth, Bluetooth, dan playback sudah diuji di perangkat fisik. Lihat checklist verifikasi di bawah.

## Isi proyek

```text
app/src/main/java/id/my/chiroot/MainActivity.java  # UI, state, cloud, player; satu file Java
app/src/main/AndroidManifest.xml
app/src/main/res/                                 # font offline, ikon, ilustrasi error, tema
app/src/test/java/                                # tes JVM terpisah dari source aplikasi
app/build.gradle
build.gradle / settings.gradle / gradle.properties
gradlew / gradlew.bat / gradle/wrapper/
.github/workflows/android-build.yml
supabase/migrations/202611080001_chiroot_native.sql
favicon.png                                       # sumber ikon asli
docs/Chiroot_Android_Master_Prompt_Updated.txt     # prompt lengkap yang diberikan
tools/                                            # pemeriksaan proyek/APK dan generator ikon
licenses/                                         # lisensi font yang dibundel
```

Semua screen aplikasi dibuat dengan View Android dari Java. Tidak ada Kotlin aplikasi, Compose, Flutter, React Native, WebView, atau ketergantungan pada URL website untuk menggambar UI. Kelas service bersarang `MainActivity.ChirootPlaybackService` adalah pengecualian yang disengaja untuk playback latar belakang; tetap berada di `MainActivity.java`.

## Fitur

- Google OAuth melalui Supabase di browser sistem, PKCE S256, deep link terpisah debug/release, pemulihan sesi dan logout; mode tamu untuk file lokal.
- Dashboard, Now Playing, mini player, vinyl Canvas, play/pause, previous/next, seek, volume/mute, shuffle/repeat, antrean yang bisa diatur, sort judul/artis, recently played, favorit, dan pencarian crate.
- MediaSessionService: satu ExoPlayer milik service, notifikasi media, kontrol lock screen/headset, audio focus dan penanganan headphone terputus.
- The Crate Supabase, pilih MP3 melalui Storage Access Framework, cover, warna vinyl/label, validasi MP3, deteksi duplikat hash, pembatalan best-effort, dan upload/retry dengan ID/path stabil. Progress jaringan ditampilkan indeterminate, bukan persen palsu.
- Quick Auto-fill dari nama file; metadata manual tetap tersedia.
- Poster Canvas, redraw, berbagi PNG, menyalin poster, dan ekspor SVG mandiri.
- Riwayat/listening report dari kejadian playback nyata, lirik LRCLIB dengan empty state, foto profil/banner warna, serta pengaturan yang disinkronkan ke Supabase.
- EQ gramofon opsional pada sesi audio ExoPlayer, dengan status dukungan sebenarnya.
- Backup/restore ZIP tanpa token, konfirmasi reset/hapus, patch notes dan credits.
- Modal error reusable dengan ilustrasi asli, **Try Again** yang mengulang operasi gagal, dan **Close**; offline/timeout/5xx dibedakan dari kesalahan input, izin, sesi, atau pembatalan login.

## Build lokal / Android Studio

Siapkan **JDK 17**, Android Studio dengan **Android SDK Platform 35** dan **Build Tools 35.0.0**. minimum perangkat tetap API 29; compile/target SDK 35 tidak menaikkan minimum perangkat.

```bash
git clone https://github.com/justmearlandolol/Chiroot-Android.git
cd Chiroot-Android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Hasil: `app/build/outputs/apk/debug/app-debug.apk`.

Di Windows gunakan `gradlew.bat`. Di Android Studio, buka folder repo, pilih JDK 17 di Gradle settings, lalu Sync Project. Wrapper memakai Gradle 8.9 dan Android Gradle Plugin 8.7.3. Jika `sdk.dir` diperlukan, Android Studio menulisnya ke `local.properties` yang tidak di-commit.Jangan memakai `gradle` sistem yang versinya tidak cocok.

### Regenerasi ikon (opsional)

Ikon sudah di-commit; build Android tidak memerlukan Python. Untuk menghasilkan ulang setelah mengganti sumber ikon:

```bash
python3 -m pip install Pillow
python3 tools/generate_icons.py
```

Generator menjaga seluruh tanda bintang di safe zone adaptive icon. PNG asli juga dibundel sebagai `drawable-nodpi/favicon.png` dan dipakai sebagai identitas visual aplikasi. Ikon kecil notifikasi adalah versi monokrom agar sesuai aturan status bar Android; itu bukan pengganti ikon launcher berwarna.

## Supabase: urutan setup

1. Buat proyek di dashboard Supabase, atau pakai proyek Chiroot yang sudah ada.
2. Buka **SQL Editor**, jalankan seluruh isi `supabase/migrations/202611080001_chiroot_native.sql`. Migration bersifat aditif dan.repeatable: tidak menghapus tabel/data pengguna.
3. Periksa **Authentication → Providers → Google** dan **Authentication → URL Configuration**.
4. Bucket `chiroot-crate` dibuat oleh migration. Audio maksimal 60 MiB; cover diproses menjadi JPEG. Policy insert/update/delete memeriksa folder pertama harus sama dengan UUID pengguna.
5. Jangan menaruh kunci `service_role`/secret key di source, Gradle, chat, atau artifact. Hanya kunci `sb_publishable_...` atau JWT ber-role `anon` yang boleh masuk APK. Kunci publik tetap dapat diekstrak dari APK; keamanan data berasal dari RLS, bukan kerahasiaan kunci ini.

### Tabel dan kompatibilitas website

Migration memakai nama yang benar-benar dipakai source website Chiroot: `profiles`, `crate_tracks`, dan `play_events`, serta menambahkan `user_settings` dan `favorite_tracks`. Kolom hash opsional mencegah duplikat audio per pengguna tanpa merusak row website lama. Profil memakai `display_name`, `pfp`, `description`, dan `banner_color`; bukan kolom baru yang tidak dikenal website. `play_events.duration_ms` menyimpan durasi mendengar nyata. Service menghitung waktu hanya saat `isPlaying`, bukan saat buffering/pause, dan membuat checkpoint walau Activity tidak terlihat. Checkpoint offline disimpan dalam outbox ber-ID stabil lalu di-upsert ketika aplikasi kembali online/terbuka; tidak ada statistik karangan. Indeks dan RLS kepemilikan disertakan.

**Privasi storage:** website Chiroot saat ini memakai bucket publik dan public object URL. Migration mempertahankan kompatibilitas itu; write tetap owner-only. Siapa pun yang memperoleh URL audio publik dapat membacanya. Jangan menganggap path acak sebagai proteksi. Untuk koleksi privat, seluruh klien (Android dan website) harus diubah bersama ke bucket privat + signed URL sebelum mengubah policy/bucket. Jangan sekadar menonaktifkan public access lalu berharap website lama tetap bekerja.

## Google OAuth dan deep link

1. Buat atau pilih Google Cloud project; konfigurasi OAuth consent screen, audience, scopes email/profile, dan test users bila aplikasi masih testing.
2. Buat OAuth Client ID tipe **Web application** untuk Supabase. Masukkan client ID/secret hanya di provider Google pada dashboard Supabase, bukan di APK.
3. Pada Google client, tambahkan authorized redirect URI callback HTTPS yang ditampilkan dashboard Supabase (bentuk `https://PROJECT_REF.supabase.co/auth/v1/callback`).
4. Aktifkan Google di Supabase. Di URL Configuration, isi Site URL website Chiroot dan tambahkan redirect Android berikut ke allow list:
   - Release: `id.my.chiroot://auth-callback`
   - Debug: `id.my.chiroot.debug://auth-callback`
5. Jalankan aplikasi, pilih login Google, selesaikan login di browser, lalu kembali melalui deep link. Aplikasi menukar authorization code + verifier PKCE dengan sesi nyata. Pembatalan tidak ditampilkan sebagai outage server.

Jika memakai skema kustom lain melalui `SUPABASE_REDIRECT_URI`, workflow/build menurunkan skema debug dengan akhiran `.debug`; tambahkan keduanya ke Supabase. Skema release dan debug sengaja berbeda supaya aplikasi debug tidak mencuri callback aplikasi release.

## Konfigurasi dan GitHub Secrets

Urutan nilai: environment variable → Gradle property → `local.properties` yang diabaikan Git → default publik Chiroot.

| Nama | Isi | Wajib untuk debug? |
|---|---|---|
| `SUPABASE_URL` | HTTPS URL proyek Supabase | Tidak; ada default proyek Chiroot |
| `SUPABASE_ANON_KEY` | Publishable/anon key | Tidak; ada default publik |
| `SUPABASE_REDIRECT_URI` | Deep link release, default `id.my.chiroot://auth-callback` | Tidak |

Di GitHub: **Settings → Secrets and variables → Actions → New repository secret**. Workflow meneruskan ketiganya sebagai environment variables tanpa mencetak nilainya. Untuk proyek sendiri, override ketiganya dan jalankan migration serta konfigurasi OAuth di proyek tersebut. Jangan memakai kunci backend lama hanya karena bentuknya JWT; periksa role-nya `anon`.

Contoh lokal di `local.properties` (jangan commit):

```properties
SUPABASE_URL=https://YOUR_PROJECT.supabase.co
SUPABASE_ANON_KEY=sb_publishable_YOUR_PUBLIC_KEY
SUPABASE_REDIRECT_URI=id.my.chiroot://auth-callback
```

### Release bertanda tangan

`app/build.gradle` mendukung signing melalui `CHIROOT_KEYSTORE_FILE`, `CHIROOT_KEYSTORE_PASSWORD`, `CHIROOT_KEY_ALIAS`, dan `CHIROOT_KEY_PASSWORD`, atau property dengan nama yang sama. Sediakan keystore hanya di lingkungan terlindungi; jangan commit `.jks`/`.keystore`. Tanpa signing config, release menghasilkan APK unsigned dan bukan untuk distribusi. Workflow publik ini hanya mengunggah debug APK, tidak membuka signing secrets ke PR tidak tepercaya.

## Arsitektur playback dan audio

- Service memiliki satu ExoPlayer; Activity hanya memakai MediaController. Membuka screen lain tidak membuat player baru. Saat Activity dihancurkan, controller dilepas tetapi service tetap memiliki player. Saat service dihancurkan, session, equalizer dan player dilepas.
- Media3 mengelola audio focus: transient loss/ducking, permanent loss, dan recovery yang diizinkan sistem. Android atau aplikasi lain tetap berhak mengambil fokus; tidak ada janji musik selalu kebal interupsi.
- `setHandleAudioBecomingNoisy(true)` dan pemantauan perangkat audio menjeda saat headphone/output eksternal terputus. Perangkat baru tidak otomatis memulai musik.
- Metadata/artwork, play/pause, previous/next dan seek tersedia melalui MediaSession. Izin notifikasi diminta pada Android 13+.
- DSP adalah **platform Equalizer** pada audio session ExoPlayer, bukan NoiseSuppressor/AcousticEchoCanceler/AGC mikrofon. Jika efek tidak tersedia, status melaporkan tidak didukung. Environmental ANC membutuhkan hardware headphone dan tidak disimulasikan aplikasi.

## Error, retry, dan aset

`app/src/main/res/drawable-nodpi/chiroot_error_sign.png` adalah gambar asli yang diberikan. Teks baked-in tetap:

- `something went wrong.`
- `Pls try again later alright :>`

Modal dipakai untuk operasi yang diminta pengguna ketika koneksi/server gagal. ConnectivityManager hanya petunjuk awal; kegagalan HTTP aktual tetap diklasifikasikan. Dialog dipakai ulang agar tidak bertumpuk, dan retry menyimpan operasi beserta parameter yang benar. Upload memakai ID dan path stabil serta upsert agar retry tidak menggandakan row. Kesalahan validasi, izin, sesi, atau OAuth yang dibatalkan tidak memakai modal outage generik.

## Backup, poster, dan sumber desain

Backup ZIP berisi metadata crate, riwayat, preferensi, dan profil; **tidak berisi access/refresh token atau audio biner**. File lokal tetap berada di lokasi asalnya. Restore menggabungkan data dengan deduplikasi dan meminta konfirmasi. Poster PNG dibagikan melalui FileProvider dan dapat disalin sebagai gambar bila aplikasi tujuan mendukung. SVG ditulis sebagai dokumen XML mandiri dengan cover tertanam data URI, bukan link ke file privat perangkat.

URL asli `https://chiroot.my.id/Main.html` tidak dapat diakses langsung dari lingkungan kerja ini. Penelitian visual dilakukan dari source `Main.html` pada repo GitHub `justmearlandolol/Chiroot-Website`: palet kertas/tinta/kayu, font Patrick Hand/Kalam/Caveat, hirarki gramofon, dan fitur crate/profil dipetakan dari source tersebut. Font dibundel agar UI tidak bergantung pada website/font CDN. Tidak ada klaim bahwa screenshot situs langsung sudah diperiksa.

## Verifikasi dan batasan pengujian

CI memeriksa file proyek, hash sumber favicon, resource ikon, tidak adanya WebView/Kotlin aplikasi, tes utilitas JVM, lint, build APK, package/SDK/label, serta resource ikon dan gambar error yang benar-benar ada di APK. Laporan tes/lint tersedia sebagai artifact `chiroot-build-reports`.

Checklist perangkat Android 10+ (belum dianggap lulus hanya karena CI):

- [ ] Instal APK artifact; ikon launcher benar dan aplikasi terbuka tanpa website.
- [ ] Login Google berhasil, batal login aman, sesi pulih setelah restart, logout.
- [ ] Pilih MP3/cover, upload, retry gagal/sukses tanpa duplikat, lagu muncul di crate.
- [ ] Play/pause/seek/next/previous/shuffle/repeat/volume dan animasi vinyl sesuai audio.
- [ ] Playback saat aplikasi di belakang, layar terkunci, notifikasi/Bluetooth controls.
- [ ] Focus loss/ducking/gain, telepon/navigasi, cabut headphone, sambungkan perangkat baru.
- [ ] Offline, timeout, respons 5xx, retry sukses/gagal, dismiss dan tidak ada dialog bertumpuk.
- [ ] Poster redraw/share/copy/SVG; laporan memakai data nyata; lirik tersedia/tidak tersedia.
- [ ] Profil tersimpan, settings/DSP sesuai dukungan perangkat, backup/restore dan reset.

Jika backend/OAuth belum dikonfigurasi, aplikasi tidak boleh pura-pura login atau upload sukses. Gunakan mode tamu untuk file lokal sambil menyelesaikan konfigurasi cloud.
