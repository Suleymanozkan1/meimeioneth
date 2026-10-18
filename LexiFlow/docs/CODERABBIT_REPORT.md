# Kod incelemesi raporu

## CodeRabbit durumu
**CodeRabbit çalıştırılmadı.** Bu ortamda CodeRabbit kurulu değil ve depoya bağlı bir CodeRabbit entegrasyonu da yok. Çalışmış gibi gösterilmedi.

Bunun yerine şunlar yapıldı:
1. **Bağımsız manuel inceleme:** Ayrı bir inceleme ajanı kodu salt-okunur olarak taradı. Her bulguyu kod yolunu izleyerek doğruladı, gramer kurallarını gerçek regex'lerle çalıştırarak denedi ve FSRS'i fsrs-rs kaynağıyla karşılaştırdı.
2. **Android lint:** `./gradlew :app:lintDebug` → 0 hata. İlk çalıştırmadaki 6 hata düzeltildi; ayrıntı TEST_REPORT.md'de.
3. **Gradle derleme uyarıları ve test paketi.**

## Bulgular ve sonuçları
| # | Önem | Bulgu | Düzeltme | Test |
|---|---|---|---|---|
| 1 | Yüksek | "say/explain/borrow + her/them" kuralları doğal cümleleri bozuyordu ("He said her name") | her/them çıkarıldı | ReviewRegressionTest.naturalEnglishIsNeverCorrected |
| 2 | Yüksek | married with / arrive to / enter in / modal to / people is / researches / many work yanlış alarm veriyordu | Bağlam koşulları eklendi: belirleyici + kişi ismi; "arrived to find" hariç; edilgen yapı hariç; "the will to", "May" hariç. people_is "kontrol et" seviyesine indi. Fiil olabilen çoğullar çıkarıldı. | aynı |
| 3 | Yüksek | do/did + -ed kuralı "Do married couples", "Did Ahmed" cümlelerini bozuyordu; "made it work", "Is it love?" | Düzenli -ed yalnızca olumsuz yardımcıdan sonra kontrol ediliyor; "it" 3. tekil kuralından çıkarıldı; "why do mistakes", "makes the exercise more fun" hariç | aynı |
| 4 | Yüksek | a/an: "an unimportant", "a used car", "an X-ray", "Plan A" | un- öneki, used/useless/unanimous/Ukrainian, tek harfler, etiket olarak "A" düzeltildi | aynı + realErrorsStillCaught |
| 5 | Yüksek | "7 a.m. and" → "And"; "i.e." → "I.e." | Kısaltmalardan sonra büyük harf kuralı uygulanmıyor; nokta ile bitişik "i" atlanıyor | aynı |
| 6 | Orta | Aynı gün "Hard" cevabı kararlılığı düşürüyordu (fsrs-rs çıkarım yolu ≥2 kullanır) | `rating >= 2` | hardOnSameDayNeverLowersStability |
| 7 | Orta | Tekrar ekranında çift dokunuş aynı kartı iki kez cevaplıyor, sonraki kartı atlıyordu | `answering` koruması | learningStepsComeBack…AndDoubleTapIsIgnored |
| 8 | Orta | Kilit ekranı boşluk sorusu, cevap tam kelime değilse çöküyordu | Tam kelime boşluk kurulamazsa TR→EN'ye düşülüyor | lockClozeFallsBackWhenAnswerIsNotAWholeWord |
| 9 | Orta | Yalnızca İngilizce tanımla içe aktarılan kartın Türkçe anlamı boştu; yeniden içe aktarma kopyalıyordu | Tanım anlam olarak saklanıyor | definitionOnlyImport… |
| 10 | Orta | Tek kelimelik ilk satır ("back") başlık sanılıp sessizce kayboluyordu | En az 2 bilinen başlık şartı + "İlk satır başlık" anahtarı | singleVocabularyWordIsNotTakenAsHeader |
| 11 | Orta | Öğrenme adımındaki kart (1–10 dk) aynı oturumda geri gelmiyordu | 20 dk içinde zamanı gelen kart kuyruğun sonuna ekleniyor | learningStepsComeBack… |
| 12 | Düşük | Düğmedeki aralık uygulanan aralıktan farklıydı (fuzz) | Önizleme aynı tohumu kullanıyor | previewWithSeedEqualsAppliedInterval |
| 13 | Düşük | Kilit ekranı cevabı iki kez kaydedilebiliyordu (yarış durumu) | Koşullu atomik `UPDATE … WHERE answeredAt IS NULL` + UI meşgul bayrağı | lockAnswerIsRecordedOnlyOnce |
| 14 | Düşük | Döndürmede derin bağlantı tekrar işleniyor; "yeni soru" tekrar üretiliyordu | Yalnızca ilk açılışta okunuyor, `setIntent`, `rememberSaveable` | manuel (cihazda doğrulanmalı) |
| 15 | Düşük | İçe aktarma raporundaki satır numaraları kayıktı; Anki `#tags/#guid column` yok sayılıyordu | Gerçek satır numaraları taşınıyor; yönergeler ve `#columns:` destekleniyor | ankiDirectivesAndRealLineNumbers |
| + | Düşük | Kilit ekranında zamanı gelmiş kartta XP iki kez veriliyordu | Kart due ise XP yalnızca FSRS tekrarından geliyor | — |
| + | Düşük | Ana sayfadaki "zamanı gelen" sayısı ve günlük görev kartı gece yarısından sonra eskiyordu | Dakikalık tetikleyiciyle yeniden sorgulama | — |

## İncelemede sorunsuz bulunanlar
- **PendingIntent:** İstek kodları çakışmıyor. `FLAG_MUTABLE` yalnızca RemoteInput için ve açık intent ile kullanılıyor (Android 14+ uyumlu).
- **Arka plan:** `goAsync` + `finish()` doğru kullanılıyor. Her `notify` öncesi bildirim izni kontrol ediliyor.
- **FSRS:** Diğer tüm formüller, varsayılan ağırlıklar ve öğrenme/yeniden öğrenme durum makinesi fsrs-rs ve py-fsrs ile uyumlu. Kilit ekranından FSRS'e yazma due koşuluna bağlı.
- **İçerik:** 444 yerleşik örneğin her birinde `answer` tam kelime olarak eşleşiyor.

## Güvenlik ve gizlilik gözden geçirmesi
- Uygulamada API anahtarı veya gizli bilgi yok; `BuildConfig.DEFAULT_BACKEND_URL` boş.
- Ağ: yalnızca HTTPS (`network_security_config`). Açık metin yalnızca yerel geliştirme adreslerinde (10.0.2.2, localhost).
- Loglarda kullanıcı metni yazılmıyor. Ağ hatalarında yalnızca yol ve istisna türü loglanıyor.
- Ham ses saklanmıyor. Konuşma ve telaffuz geçmişi süre sınırlı ve silinebilir. Cevap metni saklama kapatılabilir.
- Android yedeğine veritabanı dahil edilmiyor. `allowBackup=false`.
- **Sunucu:**
  - JWT ≥32 karakter gizli anahtar ister.
  - Görünen adı doğrular.
  - Hız sınırları: AI istekleri, kayıt ve WebSocket mesajları.
  - Metinleri temizler ve uzunluğunu sınırlar.
  - Puanlamayı sunucu tarafında doğrular.
  - Moderasyon bildirimlerini kaydeder.
