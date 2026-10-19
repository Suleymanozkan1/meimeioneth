# Gereksinim kontrol listesi

Durumlar yalnızca şunlardan biridir: IMPLEMENTED, PARTIAL, NOT_IMPLEMENTED, BLOCKED. Kaynak kodu: M = ana master prompt bölümü, E = ek geliştirme promptu.

**Özet:** 62 gereksinim:
- 45 IMPLEMENTED
- 12 PARTIAL
- 2 NOT_IMPLEMENTED
- 3 BLOCKED

**Gerçek tamamlanma:** %82. Hesap: IMPLEMENTED = 1 puan, PARTIAL = 0,5 puan, diğerleri 0 puan. Bir ekranın var olması tek başına tamamlandı sayılmadı.

| # | Kaynak | Gereksinim | Durum | Kanıt / not |
|---|---|---|---|---|
| 1 | M2 | Kotlin native, Compose, Material 3, MVVM, Coroutines/Flow, Room, DataStore, WorkManager, Navigation | **IMPLEMENTED** | app/, ARCHITECTURE.md |
| 2 | M2 | Offline-first; ağ/AI yokken çökmeden yerel alternatif | **IMPLEMENTED** | RemoteError + yerel hikâye/koç; FeatureReposTest |
| 3 | M2 | AI sağlayıcı arayüz arkasında, anahtar yalnızca sunucuda | **IMPLEMENTED** | backend/ai/AiProvider.kt, BackendClient |
| 4 | M3 | Backend: Ktor + PostgreSQL + REST + WebSocket + JWT + sunucu doğrulaması | **IMPLEMENTED** | backend/, 23 test + PostgreSQL testi |
| 5 | M4 | docs/REPOSITORIES.md (URL, yıldız, sürüm, lisans, kullanım, alternatifler) | **IMPLEMENTED** | REPOSITORIES.md |
| 6 | M5 | Kilit ekranı bildirimleri: kelime, bağlam, Türkçe (isteğe bağlı), hatırladım/hatırlamadım, uygulamada aç | **IMPLEMENTED** | NotificationHelper, NotificationTest |
| 7 | M5 | Kilit ekranında hassas içeriği gizleme | **IMPLEMENTED** | VISIBILITY_PRIVATE + publicVersion |
| 8 | M5 | Bildirim ayarları: günlük sayı, sessiz saat, aralık, zorluk, havuz, kapatma | **IMPLEMENTED** | LockSettingsCard, LockScreenAndImportTest |
| 9 | M5 | Bildirim cevabı FSRS geçmişiyle tutarlı | **IMPLEMENTED** | LockScreenRepository (yalnızca due), testler |
| 10 | M5 | Ana ekran widget'ı + kısayollar | **IMPLEMENTED** | widget/, shortcuts.xml |
| 11 | M5 | Gerçek cihaz / Android sürümlerinde doğrulama | **BLOCKED** | Emülatör yok (KVM yok); manuel test listesi |
| 12 | M6 | CSV/TSV içe aktarma: alan eşleştirme, önizleme, kodlama, duplicate, hatalı satır raporu | **IMPLEMENTED** | ImportRepository, DelimitedParser, testler |
| 13 | M6 | .apkg desteği | **PARTIAL** | anki2/anki21 + revlog; anki21b (zstd) ve medya yok |
| 14 | M6 | Anki tekrar geçmişinin korunması (uydurmadan) | **IMPLEMENTED** | HistoryReplay, apkgImportReplaysRealHistory |
| 15 | M6 | Kullanıcının desteleri düzenlemesi | **PARTIAL** | Kelime ekle/düzenle/sil/geri al, örnek ekle, askıya al; deste yeniden adlandırma/silme yok |
| 16 | M6 | Yerleşik içeriğin kaynak ve lisansı | **IMPLEMENTED** | CONTENT_SOURCES_AND_LICENSING.md |
| 17 | M7 | Zengin kelime kartı alanları | **PARTIAL** | IPA UK/US, hece, vurgu, collocation, phrasal, aile, eş/zıt, register, yaygın hata, karıştırılan, gramer; insan ses kaydı yok (TTS) |
| 18 | M7 | Anlamların ayrı öğrenme birimi olması | **IMPLEMENTED** | senses + kart/anlam |
| 19 | M7 | AI örnekleri için kalite kontrol katmanı | **PARTIAL** | Şema + hedef kelime + ≥10 örnek doğrulaması; AI örneklerine otomatik gramer denetimi yok |
| 20 | M8 | 6+ beceri boyutu ayrı takip | **IMPLEMENTED** | skill_states, ACTIVE_RECALL.md |
| 21 | M8 | Aktif hatırlama alıştırmaları (TR→EN, boşluk, kendi cümle, çeviri, durum, sesli, farklı anlam, serbest yazma, AI konuşma) | **IMPLEMENTED** | DrillScreen, StudyFlowTest.everyDrillModeBuildsRealExercises |
| 22 | M8 | "Tanıyorum ama kullanamıyorum" ayrı sinyal | **IMPLEMENTED** | flagCantUse, ActiveRecallRepository |
| 23 | M9 | Gerçek FSRS (Again/Hard/Good/Easy) + resmi testlerle karşılaştırma | **IMPLEMENTED** | Fsrs.kt, FsrsTest, ReviewRegressionTest |
| 24 | M9 | FSRS parametre optimizasyonu | **NOT_IMPLEMENTED** | Varsayılan FSRS-6 ağırlıkları |
| 25 | M9 | Ayrı aktif practice scheduler | **IMPLEMENTED** | ActivePracticeScheduler |
| 26 | M9 | Review verileri (id, zaman, rating, süre, aralık, durum, mod, sonuç) | **IMPLEMENTED** | review_logs, practice_logs |
| 27 | M10 | AI hikâye motoru (seviye, tema, uzunluk, gramer, zorluk) | **PARTIAL** | Sunucu + API anahtarı gerekir; gerçek model çağrısı test edilmedi; çevrim dışı şablon hikâye çalışıyor |
| 28 | M10 | Hikâye sonrası alıştırmalar ve değerlendirme | **IMPLEMENTED** | StoryReaderScreen |
| 29 | M11 | Unutma eğrisine göre kişisel hikâyeler, tahmin→hatırla→tamamla→kullan sırası | **IMPLEMENTED** | StoryPlanner, maskeli okuma |
| 30 | M12 | AI speaking (sesli, döküm, en önemli 1-3 nokta, öğrenilen kelimeler) | **PARTIAL** | Çevrim dışı koç + SpeechRecognizer çalışıyor; AI sohbet yolu gerçek modelle denenmedi |
| 31 | M12 | Yazılı speaking aynı değerlendirmeden | **IMPLEMENTED** | SpeakingRepository |
| 32 | M13 | ELSA tarzı telaffuz: kelime düzeyi, güvenilirlik, tekrar deneme | **PARTIAL** | ASR tabanlı anlaşılırlık + minimal çiftler; fonem/vurgu ölçümü yok (PRONUNCIATION.md) |
| 33 | M13 | Mikrofon izni, kayıt durumu, ses verisi bilgilendirmesi, süresiz saklamama | **IMPLEMENTED** | MicButton, Ayarlar, saklama süresi |
| 34 | M14 | Online yarışma: oda, eşleştirme, süre, sunucu puan doğrulaması, sonuç, liderlik, kopma | **PARTIAL** | Sunucu tarafı tam ve test edildi; uygulama istemcisi canlı sunucuya karşı uçtan uca test edilmedi; otomatik yeniden bağlanma yok (Rejoin elle) |
| 35 | M14 | Sahte oyuncu yok; bot açıkça etiketli | **IMPLEMENTED** | BotPractice |
| 36 | M14 | Yarışma değerlendirme boyutları + gerekçe + düzeltilmiş sürüm | **IMPLEMENTED** | CompetitionRules, PlayerResultView |
| 37 | M14 | Kötüye kullanım önleme: hız sınırı, raporlama, güvenli metin | **IMPLEMENTED** | backend rate limit, Report, sanitize |
| 38 | M15 | Gramer ve cümle iyileştirme ("I made a party"), mantığı öğretme, alternatifleri kabul | **IMPLEMENTED** | GrammarRules, LanguageTest, ReviewRegressionTest |
| 39 | M16/E9 | 24 öğrenme modu (her biri çalışan ekran) | **IMPLEMENTED** | MODES.md, 31 giriş |
| 40 | E4 | Oyunlaştırma: seri, XP, seviye, rozet, günlük/haftalık görev, rekorlar, grafikler | **IMPLEMENTED** | GamificationRepository, ProgressScreen |
| 41 | E4 | İlerleme: yeni/tekrar/aktif/konuşma dakikası/zayıf kelime değişimi | **IMPLEMENTED** | StatsRepository.weekly |
| 42 | M14db | Room şeması, ilişkiler, indeksler, migration + test | **IMPLEMENTED** | DATABASE.md, migrate1To2 |
| 43 | M14db | SyncQueue / çevrim dışı senkronizasyon | **PARTIAL** | Kuyruk tablosu var; kuyruğu sunucuya gönderen worker yok; bulut senkronu yok |
| 44 | M14db | Veri minimizasyonu, saklama tercihleri, silme | **IMPLEMENTED** | Ayarlar → Gizlilik |
| 45 | M2 | Erişilebilirlik, koyu/açık tema, boş/yükleniyor/hata durumları | **PARTIAL** | Tema + dinamik renk + durum ekranları + içerik açıklamaları; TalkBack denetimi ve çok dilli kaynaklar yok |
| 46 | M16 | Unit testler (FSRS, puanlama, seri, XP, dönüşüm, içe aktarma, hata) | **IMPLEMENTED** | core 41 test |
| 47 | M16 | Room DAO ve migration testleri; repository testleri; AI şema; ağ hatası | **IMPLEMENTED** | app 35 test |
| 48 | M16 | Compose UI testleri | **IMPLEMENTED** | UiFlowTest (Robolectric) |
| 49 | M16 | Uçtan uca akış (kur → içe aktar → tekrar → kaydet → plan ve istatistik) | **PARTIAL** | JVM'de (Robolectric) doğrulandı; cihazda değil |
| 50 | M16 | CodeRabbit | **BLOCKED** | Kurulu değil; manuel bağımsız inceleme yapıldı (CODERABBIT_REPORT.md) |
| 51 | M18 | Zorunlu dokümantasyon | **IMPLEMENTED** | docs/ |
| 52 | M19 | APK üretimi | **IMPLEMENTED** | dist/LexiFlow-1.2.1.apk |
| 53 | M19 | Play Store için imzalı sürüm | **BLOCKED** | Kullanıcının kendi keystore'u gerekir |
| 54 | E1 | Kilit ekranı soru tipleri dönüşümlü + doğrudan cevap + Biliyorum/Zorlandım/Kullanamıyorum | **IMPLEMENTED** | LOCK_SCREEN.md, NotificationTest |
| 55 | E2 | Yerleşik her kelime için ≥10 farklı cümle | **IMPLEMENTED** | 444 örnek / 40 kelime |
| 56 | E2 | İçe aktarılan kelimeler için ≥10 cümle | **PARTIAL** | AI zenginleştirme (sunucu gerekir), ≥10 şema ile zorunlu |
| 57 | E2 | Boşluk farklı yerde, seçenekli/ipuçlu/ipucusuz, alternatif cevaplar, farklı günlerde farklı cümle | **IMPLEMENTED** | ClozeBuilder, ExampleRotation |
| 58 | E3 | Film/dizi/müzik modu: lisanslı içerik, kaynak/zaman/lisans kaydı, açıklama + alıştırma | **IMPLEMENTED** | CultureScreens, cultural.json |
| 59 | E3 | FSRS/kart entegrasyonu | **IMPLEMENTED** | CultureRepository.cardFor / addToWordbook |
| 60 | E6 | Hikâye başarısının tekrar durumuna etkisi tanımlı, çift sayım yok | **IMPLEMENTED** | FSRS.md, ACTIVE_RECALL.md |
| 61 | E7 | Aktif üretim yolu ve farklı bağlam/gün şartı | **IMPLEMENTED** | ActiveStage, isActiveMastered |
| 62 | M10b | Çevrim dışı konuşma tanıma (sherpa-onnx) | **NOT_IMPLEMENTED** | Değerlendirildi; boyut nedeniyle ertelendi |
