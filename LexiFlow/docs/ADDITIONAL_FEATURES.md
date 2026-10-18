# Ek geliştirme promptu — etki analizi ve uygulama

Ek prompt, ana master prompt ile aynı oturumda geldi. Bu yüzden özellikler mevcut mimariye baştan dahil edildi; ayrı bir "sonradan ekleme" katmanı yok. Var olan veriyi bozmamak için Room şeması v1→v2 gerçek bir migration ile genişletildi.

| Ek gereksinim | Etki / uygulama | Durum |
|---|---|---|
| 1. Kilit ekranında öğrenme: 4 soru tipi dönüşümlü, cevap sonrası açıklama ve örnek, Biliyorum/Zorlandım/Kullanamıyorum, sıklık/sessiz saat/günlük sayı | `LockScreenRepository`, `NotificationHelper`, `QuestionActionReceiver`, `LockScreenWorker`, `lockscreen_questions` tablosu (v2), widget, kısayollar. Ayrıntı: LOCK_SCREEN.md | IMPLEMENTED (cihazda doğrulama bekliyor) |
| 2. Her kelime için ≥10 farklı cümle, boşluk farklı yerlerde, seçenek/ipucu/ipucusuz, alternatif cevaplar, farklı günlerde farklı cümle | Yerleşik 40 kelime × 10–13 özgün örnek (444 cümle). `ExampleRotation` en uzun süredir gösterilmeyen örneği seçer (`examples.lastShownAt`). `acceptedAlternatives`. İçe aktarılan kelimeler için AI zenginleştirme ≥10 örneği şema ile zorunlu kılar. | IMPLEMENTED (yerleşik) / PARTIAL (içe aktarılan kelimeler AI gerektirir) |
| 3. Film/dizi/müzik bağlamı, lisanslı içerik, eser/kaynak/zaman/lisans kaydı, FSRS ile entegrasyon | `content_sources`, `cultural_contexts` (v2). 31 doğrulanmış öğe; kullanıcı .srt/.txt içe aktarma; deck kelimesine bağlı beceri kaydı; "defterime ekle" ile FSRS kartı. Ayrıntı: CONTENT_SOURCES_AND_LICENSING.md | IMPLEMENTED |
| 4. Duolingo tarzı oyunlaştırma | XP işlemleri, seviye, seri (yerel gün, geçmiş değişmez), 13 rozet, haftalık 3 görev (haftaya göre dönen), günlük görev, kişisel rekorlar, 14 günlük grafikler | IMPLEMENTED |
| 5. Anki, FSRS, SQLite'ı koru; migration | FSRS-6 portu, revlog yeniden oynatma, ayrı aktif geçmiş, MIGRATION_1_2 + test | IMPLEMENTED |
| 6. Unutma eğrisi hikâyeleri, iki kez saymama | `StoryPlanner` (R, gecikme, aktif zayıflık, son 6 saat hariç). Hikâye FSRS'e yazmaz. | IMPLEMENTED (AI hikâyesi sunucu gerektirir; çevrim dışı şablon var) |
| 7. Pasif → aktif ayrı problem | 7 beceri, aktif üretim yolu, ≥3 bağlam + ≥2 gün şartı. Ayrıntı: ACTIVE_RECALL.md | IMPLEMENTED |
| 8. AI speaking + ELSA tarzı telaffuz (fonem iddiası yok) | Konuşma koçu (AI veya çevrim dışı), sesli+yazılı, ayrı boyutlar; ASR tabanlı kelime düzeyi anlaşılırlık, minimal çiftler, güven düşükse belirsiz. Ayrıntı: PRONUNCIATION.md | PARTIAL (fonem düzeyi puanlama yok) |
| 9. 24 öğrenme modu | MODES.md'deki tablo; 24 + 7 ek mod | IMPLEMENTED |
| 10. Test, APK | TEST_REPORT.md; debug ve release APK üretildi | IMPLEMENTED (emülatör testi BLOCKED) |
