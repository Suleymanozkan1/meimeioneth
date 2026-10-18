# FSRS entegrasyonu

## Sürüm ve kaynak
- **Algoritma:** FSRS-6. Formüller open-spaced-repetition/fsrs-rs **v6.6.2** (commit `0a57374`) `src/model_v6.rs`, `src/inference_v6.rs` dosyalarından bağımsız olarak Kotlin'e aktarıldı. Lisans BSD-3-Clause.
- **Parametreler:** 21 varsayılan ağırlık `FSRS6_DEFAULT_PARAMETERS`'tan birebir alındı (w20 = decay = 0.1542). 19 parametreli (FSRS-5) diziler w19=0, w20=0.5 ile tamamlanır.
- **Kod:** `core/src/main/kotlin/com/lexiflow/core/fsrs/Fsrs.kt` (model), `FsrsScheduler.kt` (kart durum makinesi).

## Terimler (doğru kullanım)
| Terim | Anlamı |
|---|---|
| **Stability (S)** | Hatırlama olasılığının %100'den %90'a düşmesi için geçen gün sayısı |
| **Difficulty (D)** | 1–10 arası; kararlılığın artışını zorlaştırır |
| **Retrievability (R)** | t gün sonra hatırlama olasılığı. R(t,S) = (1 + FACTOR·t/S)^(−decay), FACTOR = 0.9^(−1/decay) − 1 |

FSRS genel bir "unutma eğrisi görseli" değildir. Her tekrar sonrasında S ve D'yi güncelleyen, geçmişten öğrenilmiş parametrelere dayalı bir hafıza modelidir.

## Durum makinesi (py-fsrs / Anki ile uyumlu)
- **NEW → LEARNING:** Öğrenme adımları 1 dk, 10 dk.
  - Again: adım 0'a döner.
  - Hard: adımı tekrarlar (ilk adımda iki adımın ortalaması).
  - Good: sonraki adıma geçer, son adımdan sonra mezun olur.
  - Easy: hemen mezun olur.
- **REVIEW:** Hard/Good/Easy → `nextInterval(S, desiredRetention)` gün. Again → lapses+1, RELEARNING (10 dk).
- **Aynı gün tekrarı** (geçen süre yuvarlanmış 0 gün): kısa vadeli kararlılık formülü uygulanır. fsrs-rs çıkarım yolu (`next_state_scalar`) gibi Hard/Good/Easy'de kararlılık azalmaz; yalnızca Again düşürür.
- **Fuzz:** Anki'nin yayımlanmış aralıklarıyla (2.5–7 g: ±%15, 7–20 g: ±%10, 20+ g: ±%5). Tohum kart kimliğine bağlıdır, yani deterministiktir. Düğmelerdeki aralık önizlemesi aynı tohumu kullanır; gösterilen aralık, uygulanan aralığın aynısıdır.
- **İstenen hatırlama oranı:** Ayarlardan %80–95 (varsayılan %90).

## Veri saklama
- **cards tablosu:** FSRS durumu (`state, step, stability, difficulty, dueAt, lastReviewAt, reps, lapses, scheduledDays`).
- **review_logs tablosu (eklemeli):**
  - rating, önceki durum, geçen gün, planlanan gün, aralık (ms)
  - sonraki S/D, tekrar anındaki R
  - süre
  - kaynak: `APP | NOTIFICATION | WIDGET | IMPORT_ANKI`
  - mod ve yerel tarih

## Anki geçmişinin aktarımı
- `.apkg` içindeki gerçek `revlog` satırları (ease 1–4, type 0–3) zaman sırasıyla FSRS'ten yeniden geçirilir (`HistoryReplay`).
- Elle yeniden planlama (type 4) ve ease 0 kayıtları cevap sayılmaz.
- Geçmişi olmayan kart NEW kalır; geçmiş **uydurulmaz**.
- Aktarılan loglar `stateBefore = -1` ve `source = IMPORT_ANKI` ile işaretlenir. Bu yüzden "bugünkü yeni kart" ve "hatırlama oranı" istatistiklerine karışmaz.

## Kilit ekranı ve diğer modlarla ilişki
- **Kilit ekranı cevabı FSRS'e yalnızca kart gerçekten zamanı geldiğinde (due) işlenir:**
  - yanlış → Again
  - "Biliyorum" → Good
  - "Zorlandım" → Hard
  - "Tanıyorum ama kullanamıyorum" → Good + aktif üretim kuyruğu

  Böylece erken tekrar yapılmaz ve geçmiş tutarlı kalır.
- **Hikâyeler, tatbikatlar, konuşma ve telaffuz FSRS'e yazmaz.** Yalnızca aktif beceri tablosunu günceller (ayrıntı: ACTIVE_RECALL.md). Aynı başarı iki kez sayılmaz.

## Testler (core/src/test/.../FsrsTest.kt)
Referans değerler aynı formüllerle Python'da bağımsız hesaplandı (betik TEST_REPORT.md'de):
- R(S,S) = 0.9
- `next_interval_remains_fractional` (fsrs-rs testi): 121.01552
- İlk S/D değerleri
- Uzun vadeli Good/Again/Hard/Easy güncellemeleri
- Kısa vadeli güncellemeler
- Öğrenme adımları, relearning, düğme sırası (Hard < Good < Easy), fuzz aralığı, geçmiş yeniden oynatma

## Bilinen sınırlar
- Parametre optimizasyonu (kişisel ağırlıklar) yok; varsayılan FSRS-6 ağırlıkları kullanılır.
- Gün sınırı, geçen sürenin en yakın tam güne yuvarlanmasıyla belirlenir (fsrs-rs gibi). Anki'nin "gün başlangıcı saati" ayarı yok.
