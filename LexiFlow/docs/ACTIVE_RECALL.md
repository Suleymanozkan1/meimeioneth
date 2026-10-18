# Pasiften aktife: ayrı aktif kullanım planlayıcısı

FSRS yalnızca **tanıma** kartını (İngilizce kelime → anlam) planlar. Aktif beceriler ayrı tabloda (`skill_states`) ve ayrı bir algoritmayla (`core/.../active/ActivePractice.kt`) tutulur. FSRS'in resmî hesabı değiştirilmez.

## Beceriler
| Beceri | Ölçüldüğü yer |
|---|---|
| RECOGNITION | FSRS kartı (retrievability) |
| MEANING_RECALL | İng→Tr, dinleme, hikâyede anlam soruları |
| RECALL | Türkçeden İngilizce üretme (yazarak) |
| PRODUCTION | Kendi cümlesi, çeviri, hikâye/kültür üretim soruları |
| SPEAKING | Sesli cevaplar (konuşma, sesli üretim) |
| PRONUNCIATION | Telaffuz laboratuvarı ve shadowing (anlaşılırlık) |
| CONTEXT | Boşluk doldurma, cümle sıralama, dikte, bağlam seçimi |

## Kurallar
- **Etkin puan** = puan × (1 − 0.15 × görünen ipucu seviyesi). Yalnızca ipucuyla bulunan cevap daha az sayılır.
- **mastery** ← mastery + 0.35·(etkin − mastery). Bu üstel ortalamadır; tek bir cevap ustalık ilan ettirmez.
- **Başarılı (≥0.7):**
  - İpucu bir kademe azalır (anlam+ilk harf+bağlam → anlam+ilk harf → ilk harf → yok).
  - Sonraki tekrar aralığı 1→3→7→16→35→75 gün × (0.5+mastery) × farklı bağlam bonusu.
- **Kısmi (0.4–0.7):** 12 saat sonra; ipucu aynı kalır.
- **Başarısız:** 20 dakika sonra; ipucu bir kademe artar.
- **"Tanıyorum ama kullanamıyorum":**
  - RECALL/PRODUCTION/SPEAKING ustalığı 0.3 ile sınırlanır.
  - İpuçları geri gelir, kart hemen sıraya girer ve öncelik +1.5 olur.
  - İşaret yalnızca **ipucusuz** başarılı üretimden sonra kalkar.
- **Aktif kullanımda ustalık için şunların hepsi gerekir:**
  - PRODUCTION mastery ≥ 0.75
  - ≥3 farklı bağlamda doğru üretim
  - ≥2 farklı günde başarı

## Aktif Üretim Yolu (adım sırası)
1. Türkçeden İngilizceyi hatırla (TR→EN)
2. Boşluk doldur (ipucu düzeyine göre seçenekli → ipuçlu → ipucusuz)
3. İpucusuz kendi cümleni yaz
4. Sesli kullan (konuşma)
5. Farklı bağlamda yeniden test (çeviri, farklı örnek cümle, farklı gün)

## Öncelik (kuyruk sıralaması)
`zamanı geldi (1 + gecikme·0.1) + (1 − mastery) + max(0, R_tanıma − mastery_üretim) + işaret(1.5)`

Tanıma yüksek ama üretim düşükse (tanıyor ama kullanamıyor), fark puanı öne çıkarır.

## Hikâyeler
- Okumak FSRS tekrarı sayılmaz. Hikâye soruları ilgili kartın aktif becerisine (MEANING_RECALL/CONTEXT/PRODUCTION) yazılır.
- Böylece başarı iki kez sayılmaz ve FSRS parametreleri değişmez.

## XP
Ödüller öğrenme davranışına bağlıdır:
- Tekrar: 2
- Gecikmiş tekrar: 4
- İpucusuz aktif hatırlama: 6
- İpucusuz cümle üretimi: 10 (ipuçluda yarısı)
- Konuşma turu: 8
- Telaffuz: 4
- Hikâye: 20
- Günlük görev: 30

Uygulamayı açmak XP vermez.
