# Açık kaynak repolar — değerlendirme

Bilgiler **2026-10-18** tarihinde doğrulandı:
- Yıldız sayıları github.com sayfalarından okundu. GitHub yuvarlanmış değer gösterir (ör. "32.1k").
- Son commit ve lisans bilgisi, repolar `git clone --depth 1` ile çekilip `git log -1` ve LICENSE dosyasından okundu.
- Sürümler `git ls-remote --tags` ile alınan en yüksek semver etiketidir.

Yüksek yıldız sayısı tek başına kalite göstergesi kabul edilmedi. Hiçbir repo projeye kopyalanmadı. Kullanılan tek dış kod bağımlılıkları AndroidX/Jetpack kütüphaneleri (Maven), OkHttp, kotlinx ve Ktor'dur.

| Repo | Yıldız | Son commit (HEAD) | Sürüm | Lisans |
|---|---|---|---|---|
| [ankitects/anki](https://github.com/ankitects/anki) | 32.1k | c3139c6 · 2026-10-16 | 26.09.3 | AGPL-3.0-or-later (bazı kısımlar farklı) |
| [open-spaced-repetition/fsrs4anki](https://github.com/open-spaced-repetition/fsrs4anki) | 4.1k | ff7c85c · 2026-08-14 | v6.1.3 | MIT |
| [open-spaced-repetition/fsrs-rs](https://github.com/open-spaced-repetition/fsrs-rs) (ek, resmi referans) | 445 | 0a57374 · 2026-10-09 | v6.6.2 | BSD-3-Clause |
| [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | 15.3k | 1a9d234 · 2026-10-17 | v1.13.8 | Apache-2.0 |
| [androidx/androidx](https://github.com/androidx/androidx) | 6.1k | 9a589fa (ls-remote HEAD) | kütüphane bazlı | Apache-2.0 |
| [android/architecture-samples](https://github.com/android/architecture-samples) | 45.9k | ee66e15 · 2025-07-17 | — | Apache-2.0 |
| [googlesamples/mlkit](https://github.com/googlesamples/mlkit) | 4.3k | f104783 · 2026-07-20 | — | Apache-2.0 |
| [socketio/socket.io](https://github.com/socketio/socket.io) | 63.2k | 1eaa582 · 2026-09-29 | 4.7.5 | MIT |

---

## Anki — referans (kod kullanılmadı)
- **Amaç:** Not/kart/deste ayrımı, revlog yapısı, öğrenme durumları, içe/dışa aktarma formatları.
- **Kullanılan bölüm:** Veri modeli kavramları ve `.apkg` formatı. Desteklenen yapılar `collection.anki2` / `collection.anki21` (SQLite) içindeki `col.models`, `notes`, `cards`, `revlog` tabloları ile şema 18'deki `fields`/`decks` tablolarıdır. Desteklenmeyen: `collection.anki21b` (zstd).
- **Lisansın etkisi:** AGPL-3.0. LexiFlow Anki kodunu **kopyalamaz ve bağlamaz**; yalnızca dosya formatını okur (birlikte çalışabilirlik). Bu yüzden AGPL yükümlülüğü doğmaz.
- **Android uyumu:** Masaüstü uygulaması taşınmadı; mobil deneyim özgün olarak tasarlandı.
- **Neden:** Kullanıcının Anki destelerini ve tekrar geçmişini korumak.

## FSRS (fsrs4anki + fsrs-rs) — algoritma referansı
- **Amaç:** Aralıklı tekrar zamanlaması.
- **Kullanılan bölüm:** fsrs-rs `src/model_v6.rs` ve `src/inference_v6.rs` (v6.6.2, commit 0a57374) içindeki FSRS-6 formülleri ve 21 varsayılan parametre (`FSRS6_DEFAULT_PARAMETERS`).
- **Kullanım şekli:** Kotlin'e bağımsız port (`core/.../fsrs/Fsrs.kt`). Referans değerler aynı formüllerle ayrı bir Python betiğinde hesaplandı ve unit testlerde karşılaştırıldı. Ayrıntı: `docs/FSRS.md`.
- **Optimizer:** Taşınmadı. Kişisel parametre optimizasyonu için fsrs-rs gerekir; ileride sunucu tarafında yapılabilir.
- **Lisansın etkisi:** BSD-3 ve MIT, izin verici lisanslardır. Atıf `docs/FSRS.md` ve uygulama içi "Hakkında" bölümünde.
- **Android uyumu:** Saf Kotlin, JVM ve Android'de aynı kod çalışır.
- **Alternatifler:** fsrs-kotlin (resmi değil), JNI ile fsrs-rs. JNI; NDK, boyut ve bakım maliyeti nedeniyle seçilmedi.
- **Neden FSRS-6:** fsrs-rs'teki FSRS-7 yeni, varsayılan parametreleri ve iki kararlılık bileşeni farklı. FSRS-6 ise Anki'nin yaygın kullandığı kararlı sürüm.

## sherpa-onnx — değerlendirildi, şimdilik eklenmedi
- **Değerlendirme:** Android için çevrim dışı streaming ASR, VAD ve TTS sunuyor (Apache-2.0).
- **Engeller:**
  - İngilizce modeller tipik olarak onlarca–yüzlerce MB.
  - APK'ya gömmek boyutu ciddi büyütür.
  - İlk sürümde model indirme altyapısı gerekmeden Android'in `SpeechRecognizer` servisi kullanıldı (`EXTRA_PREFER_OFFLINE` ile cihazdaki model tercih edilir).
- **Önemli not:** ASR çıktısı fonem düzeyinde telaffuz puanı değildir. sherpa-onnx de doğrudan "pronunciation assessment" sunmaz. Fonem hizalama için ayrıca CTC tabanlı bir fonem modeli ve forced alignment gerekir. Bkz. `docs/PRONUNCIATION.md`.
- **Sonraki adım:** İsteğe bağlı "çevrim dışı ses paketi" indirme ve sherpa-onnx ile streaming ASR (BLOCKED değil, PARTIAL/plan).

## AndroidX — kullanıldı
- **Kullanılan:**
  - Compose BOM 2024.12.01, Material 3
  - Navigation Compose 2.8.5
  - Room 2.6.1 (KSP)
  - WorkManager 2.10.0
  - DataStore 1.1.1
  - Lifecycle 2.8.7
  - Test kütüphaneleri
- **Lisans:** Apache-2.0.
- **Sürüm kararı:** Bu ortamda derleme araçlarıyla uyumluluğu kanıtlanmış kararlı sürümler seçildi (AGP 8.7.3, Kotlin 2.1.0). Lint daha yeni sürümler olduğunu bildiriyor (GradleDependency uyarıları). Yükseltme ayrı bir adım olarak önerilir.

## Android Architecture Samples — tasarım referansı
- **Alınan prensipler:** Katman ayrımı (UI → ViewModel → Repository → DAO), test edilebilir repository'ler, StateFlow ile UI durumu.
- **Kopyalanmayan:** Kod kopyalanmadı. Hilt yerine küçük bir manuel `AppContainer` seçildi; daha az derleme karmaşıklığı getiriyor.

## Google ML Kit örnekleri — incelendi, kullanılmadı
- ML Kit'te konuşma tanıma veya telaffuz puanlama API'si yok. Mevcut API'ler görüntü/metin ağırlıklı.
- Bu nedenle projeye eklenmedi; gereksiz bağımlılık olurdu.

## Socket.IO — incelendi, Ktor WebSockets seçildi
- Socket.IO istemci ve sunucuda kendi protokolünü gerektirir (Android için `socket.io-client-java`).
- Backend Kotlin/Ktor olduğundan Ktor'un yerleşik WebSocket desteği ve OkHttp WebSocket istemcisi seçildi. Bu ek bağımlılık getirmez.
- Protokol mesajları `core/.../competition/Competition.kt` içinde, kotlinx.serialization ile tanımlı ve paylaşılıyor.
