# LexiFlow — Mimari

## Modüller
```
LexiFlow/
├─ core/     Saf Kotlin (JVM). Android'e bağımlı değil; uygulama ve sunucu ortak kullanır.
│   ├─ fsrs/         FSRS-6 modeli, kart zamanlayıcı (öğrenme adımları, fuzz), geçmiş yeniden oynatma
│   ├─ active/       Aktif kullanım becerileri ve ayrı practice scheduler (ipucu merdiveni)
│   ├─ language/     Morfoloji, gramer/collocation kuralları, cümle değerlendirici, gramer alıştırmaları
│   ├─ exercise/     Cevap kontrolü, boşluk doldurma, örnek döndürme, çeldiriciler, soru tipi rotasyonu
│   ├─ importer/     CSV/TSV/Anki düz metin ayrıştırıcı, alan eşleştirme, tekrar (duplicate) tespiti
│   ├─ pronunciation/ ASR n-best tabanlı kelime düzeyi anlaşılırlık + minimal çiftler
│   ├─ story/        Unutma eğrisi hedef seçimi + çevrim dışı şablon hikâye
│   ├─ gamification/ Seri, seviye, rozet, haftalık görev
│   ├─ competition/  Yarışma kuralları, kelime bankası, WebSocket protokol mesajları
│   └─ ai/           AI istek/cevap sözleşmeleri + şema doğrulaması
├─ app/      Android (Kotlin, Jetpack Compose, Material 3)
│   ├─ data/db       Room varlıkları, DAO'lar, migration (v1→v2)
│   ├─ data/settings DataStore (AppSettings / UserProfile)
│   ├─ data/repo     Repository/use-case katmanı (Study, ActiveRecall, Session, LockScreen, Import, Story, Speaking, …)
│   ├─ data/remote   BackendClient (OkHttp, REST), BattleSocket (WebSocket)
│   ├─ data/importer .apkg okuyucu
│   ├─ domain        Alıştırma üretimi ve puanlama
│   ├─ notifications Kilit ekranı bildirimleri, BroadcastReceiver, WorkManager worker
│   ├─ widget        Ana ekran widget'ı
│   ├─ speech        SpeechRecognizer ve TextToSpeech sarmalayıcıları
│   └─ ui            Compose ekranları, tema, bileşenler, gezinme
└─ backend/  Ktor sunucusu (REST + WebSocket + JWT + PostgreSQL / bellek içi depo + AI proxy)
```

## Katmanlar ve veri akışı
`Compose ekranı → ViewModel (StateFlow) → Repository → Room DAO / DataStore / BackendClient`

- **Offline-first:** Bütün çekirdek öğrenme (kartlar, FSRS, aktif üretim, hikâye şablonları, değerlendirme, telaffuz, bot yarışması, istatistikler) cihazda çalışır. Sunucu yalnızca şunlar için gerekir: AI içerik, AI konuşma, kelime zenginleştirme, online yarışma.
- **Hata davranışı:** Ağ veya AI hatası `RemoteError` türleriyle (Türkçe mesaj) döner. Hikâye ve konuşma bu durumda yerel alternatife düşer; uygulama çökmez.
- **Gizli anahtarlar:** Uygulamada API anahtarı yoktur. Uygulama yalnızca anonim JWT taşır. Sağlayıcı anahtarı (`ANTHROPIC_API_KEY`) sadece sunucu ortam değişkenindedir.
- **DI:** `di/AppContainer` (manuel). Testler aynı container'ı bellek içi DB ve sahte saat (`FakeClock`) ile kurar.

## Önemli kararlar
| Karar | Gerekçe |
|---|---|
| Native Kotlin + Compose | Kilit ekranı bildirimleri, mikrofon, WorkManager ve widget'a doğrudan erişim |
| Ortak `core` modülü | Yarışma puanlaması istemcide ve sunucuda **aynı** deterministik kodla yapılır (sunucu tarafı doğrulama) |
| FSRS-6 Kotlin portu | Resmi formüllerle birebir; referans değerlerle test edildi |
| Aktif kullanım ayrı tablo | FSRS durumunu bozmaz; "tanıyorum ama kullanamıyorum" ayrı sinyal |
| Kural tabanlı değerlendirici + isteğe bağlı AI | Deterministik, açıklanabilir puan; AI yalnızca açıklama ve bağlam için, hedef kelime kontrolünü geçersiz kılamaz |
| SpeechRecognizer (sherpa-onnx değil) | APK boyutu; model indirme gerektirmeden çalışır (bkz. PRONUNCIATION.md) |
| Ktor WebSocket (Socket.IO değil) | Kotlin backend ile doğal; ek protokol gerektirmez |
| Release R8 kapalı | Küçültülmüş derleme gerçek cihazda doğrulanmadan açılmadı |

## Arka plan işleri
- `LockScreenWorker` her 15 dakikada bir çalışır (WorkManager alt sınırı). Ama yalnızca **anti-spam kapısı** izin verirse soru gönderir: etkin olmalı, sessiz saat dışında olmalı, günlük sınırın altında olmalı, aralık dolmuş olmalı.
- Aynı çalışmada veri saklama süresi temizliği (`MaintenanceRepository.applyRetention`) yapılır.
