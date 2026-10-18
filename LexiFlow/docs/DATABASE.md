# Veritabanı (Room / SQLite)

Şema dosyaları `app/schemas/com.lexiflow.data.db.AppDatabase/{1,2}.json` (Room tarafından dışa aktarılır). Mevcut sürüm **2**.

## Varlıklar (istenen modellerle eşleşme)
| İstenen model | Tablo / yer | Not |
|---|---|---|
| Deck | `decks` | |
| Note / Word | `words` | Anki "note" karşılığı; zengin bilgi `richJson` |
| WordSense | `senses` | Her anlam ayrı öğrenme birimi |
| Card + FSRSState | `cards` | FSRS durumu kartta (1:1, tekrar yok) |
| ExampleSentence / WordExample / FillBlankExercise | `examples` | Boşluk doldurma `answer` alanından türetilir; denemeler `practice_logs`'a gider (tekrarlanan veri yok) |
| Collocation | `words.richJson` | Planlanmayan bilgi JSON'da |
| ReviewLog | `review_logs` | Eklemeli FSRS geçmişi |
| ActiveRecallState | `skill_states` (PK cardId+skill) | |
| ActiveRecallAttempt | `practice_logs` | Kilit ekranı/hikâye/konuşma/kültür kaynaklı denemeler de burada (`source`) |
| LockScreenQuestion | `lockscreen_questions` (v2) | |
| PronunciationAttempt | `pronunciation_attempts` | Ses değil, yalnızca döküm ve sonuç |
| SpeakingSession / SpeakingTurn | `speaking_sessions`, `speaking_turns` | |
| Story / StoryWord / StoryAttempt | `stories`, `story_words`, `story_attempts` | `story_words.occurrences`: kelime hikâyede kaç kez geçti |
| ContentSource / CulturalContext | `content_sources`, `cultural_contexts` (v2) | Lisans durumu zorunlu alan |
| XPTransaction | `xp_transactions` | Eklemeli |
| Streak / StreakRecord | `activity_days` | Yerel gün başına satır; seri hesaplanır, saklanmaz |
| Achievement | `achievements` | |
| DailyChallenge / DailyMission | `daily_challenges` | Haftalık görevler loglardan hesaplanır (saklanmaz) |
| Competition / CompetitionSubmission | `competitions`, `competition_submissions` | |
| SyncQueue | `sync_queue` | Çevrim dışı oluşturulan giden işlemler (moderasyon bildirimi) |
| UserProfile / AppSettings | DataStore `settings` | Görünen ad, seviye, anonim sunucu belirteci, tercihler |

## İlişkiler ve bütünlük
- Yabancı anahtarlar `ON DELETE CASCADE`: deste → kelime → anlam → örnek / kart → loglar ve beceriler.
- `cards.senseId` UNIQUE: her anlam için tek tanıma kartı.
- İndeksler: `cards.dueAt`, `review_logs.reviewedAt/localDate`, `practice_logs.at/localDate/skill`, `skill_states(skill,dueAt)`, `words.lemmaKey`.

## Zaman ve saat dilimi
- Tüm zaman damgaları UTC epoch ms.
- Günlük toplamlar etkinlik anındaki **yerel tarih** ile (`localDate`, `activity_days.tzId`) saklanır. Saat dilimi değişse de geçmiş günler yeniden yazılmaz.

## Silme / geri alma
- Kelime silmede bir anlık görüntü tutulur. Snackbar'daki "Geri al" kelimeyi, anlamları ve örnekleriyle geri yükler.
- FSRS geçmişi geri gelmez; bu kullanıcıya açıkça söylenir.
- Kartlar askıya alınabilir veya sıfırlanabilir; sıfırlama geçmişi silmez.

## Migration stratejisi
- Her şema değişikliği yeni bir `Migration` ve dışa aktarılmış şema JSON'u ile yapılır. Yıkıcı migration (`fallbackToDestructiveMigration`) **kullanılmaz**.
- `MIGRATION_1_2`: `content_sources`, `cultural_contexts`, `lockscreen_questions` tabloları eklendi. Test: `DatabaseAndMigrationTest.migrate1To2KeepsDataAndAddsTables` (v1 verisi korunur, v2 şeması doğrulanır).

## Çevrim dışı senkronizasyon
- Bulut senkronizasyonu bu sürümde yok (NOT_IMPLEMENTED). Tüm öğrenme verisi cihazdadır.
- Sunucuya giden tek kullanıcı verileri şunlardır:
  - AI istekleri (yalnızca kullanıcı AI'ı açarsa)
  - yarışma cevapları
  - kuyruklanan moderasyon bildirimleri (`sync_queue`)
- Çakışma çözümü gerekmez, çünkü sunucu öğrenme verisinin sahibi değildir.

## Kişisel veri minimizasyonu
- Hesap anonimdir (yalnızca görünen ad).
- Ham ses hiç saklanmaz.
- Konuşma ve telaffuz geçmişi için saklama süresi ayarlanabilir (varsayılan 30 gün). Toplu silme düğmeleri var.
- Cevap metni saklama kapatılabilir.
- Android bulut yedeğine veritabanı dahil edilmez (`data_extraction_rules.xml`).
