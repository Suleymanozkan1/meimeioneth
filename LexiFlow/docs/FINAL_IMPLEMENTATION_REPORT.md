# LexiFlow — Son uygulama raporu

## Sonuç
LexiFlow, gerçek kodla geliştirilmiş, derlenen ve test edilen bir native Android uygulamasıdır (Kotlin, Jetpack Compose, Material 3, Room, DataStore, WorkManager). İsteğe bağlı bir Ktor backend'i ve iki tarafın paylaştığı saf Kotlin bir `core` modülü vardır.

Temel öğrenme deneyimi **tamamen çevrim dışı** çalışır:
- FSRS-6 kart tekrarı
- Aktif kullanım alıştırmaları
- Kilit ekranı soruları
- Hikâyeler (şablon)
- Konuşma koçu
- Telaffuz laboratuvarı
- Bot yarışması
- İstatistikler

AI içerik ve online yarışma için sunucu gerekir. Bu özellikler sunucusuz çalışıyormuş gibi gösterilmez.

**Gereksinim bazında gerçek tamamlanma: %82** (62 gereksinim: 45 IMPLEMENTED, 12 PARTIAL, 2 NOT_IMPLEMENTED, 3 BLOCKED). Ayrıntı: REQUIREMENTS_CHECKLIST.md.

## 1.8.0 değişiklikleri
Kelime seçimi ve sıralaması içe aktarılan Anki destesine göre kalır. Frekans sıralaması, seviye tespit testi ve ayrı okuma modu eklenmedi; okuma ihtiyacını AI Hikâyeleri karşılıyor.

1. **Hata günlüğü (`mistakes` tablosu):**
   - Boşluk Doldurma, Cümle Oluşturma, Kelime Üretimi, Çeviri, Speaking (sesli/yazılı koç turları), Dikte, Gramer ve kelime hatırlama hataları tek tabloya yazılır: kelime, hata türü, kullanıcının cevabı, doğru cevap ve tarih.
   - Hata türleri: yanlış kelime, yazım hatası, yanlış biçim, yanlış edat/kalıp, söz dizimi, hedef kelime kullanılmadı, gramer (kural kimliğiyle), kelime kullanımı, dinleme/dikte, anlam karıştırma.
   - Doğru kabul edilen serbest cümledeki kesin gramer hatası da kaydedilir.
   - İlerleme ekranında **"Bu haftanın en sık 5 hatası"** bölümü var. Gramer hataları kurala göre gruplanır ve "Gramer konusunu çalış" o kuralın alıştırmalarıyla Gramer Antrenörü'nü açar. Kelime hataları "… kelimesini tekrar et" ile o kelimeyle başlayan Zayıf Kelimeler oturumunu açar (`drill/{mode}?focus=`).
2. **Gerçek aktif kullanım ölçümü:**
   - Bir kelime, Kelime Üretimi, Cümle Oluşturma veya Speaking'de **ipucusuz ve doğru** (puan ≥ 0,7) kullanıldığında "aktif" sayılır. Çeviri ve tanıma alıştırmaları sayılmaz.
   - Tekrar edilmiş ama henüz aktif olmayan kelimeler "pasif" sayılır.
   - İlerleme ekranında aktif ve pasif sayısı ayrı gösterilir, 30 günlük iki çizgili grafikle birlikte. Veri mevcut `practice_logs` ve `review_logs` kayıtlarından hesaplandığı için geçmiş çalışmalar da sayılır.
   - Ana sayfadaki **"Aktif üretim listen"** kartı bu sayıları gösterir. Aktif Üretim Yolu önce pasif kelimeleri sıraya alır.
3. **Kelime ailesi ve kalıplar:**
   - `assets/families/families.json` LexiFlow'un özgün içeriğidir: 2131 kelime için 6826 türev (örn. decide → decision, decisive, indecisive) ve 786 phrasal verb, Türkçe anlamlarıyla.
   - Bunlara paketli Anki destelerindeki kelime ailesi notları, phrasal verb destesi ve sözlükteki kolokasyonlar eklenir.
   - Kartın arka yüzünde, Günlük Kelimeler'de, Otomatik Mod tanıtım kartında ve kelime sayfasında kısa bir **"Kelime ailesi"** bölümü görünür.
   - İçe aktarma, "✨ zenginleştir" ve hızlı ekleme bu alanları yalnızca boşsa doldurur. FSRS verisine dokunulmaz.
4. **5 dakikalık mod:**
   - Otomatik Mod kartında "⏱ Bugün sadece 5 dk var" seçeneği var. Seçilen süre ayarı değişmez, bu yalnızca o günkü seansı kısaltır.
   - Yarım kalan seans kısaltılırsa o gün FSRS'e işlenmiş kartlar korunur (çift tekrar olmaz).
   - Bitirilen her Otomatik Mod seansı, 5 dakikalık olan da, günün **minimum görevi** sayılır ve seriyi sürdürür (XP hedefine ulaşılmasa da).
5. **AI içerik geri bildirimi (`ai_content_flags` tablosu):**
   - Şu içeriklerde **"⚑ Yanlış/yetersiz"** butonu var:
     - alıştırmalardaki AI geri bildirimi;
     - Speaking koçunun AI açıklaması;
     - AI hikâyeleri;
     - LexiFlow/AI kaynaklı örnek cümleler (kullanıcının kendi ve içe aktarılan cümleleri hariç).
   - İşaret kaydedilir, içerik önbellekten (veritabanından) silinir ve sunucu bağlıysa yeniden üretilir: hikâye aynı kelimelerle yeniden yazılır, açıklama yeniden istenir, cümle yerine AI yeni cümleler üretir.
   - Sunucu yoksa içerik yalnızca kaldırılır ve bu açıkça söylenir.
6. **Ses kalitesi:**
   - TTS, aksan için kurulu en doğal sesi otomatik seçer: en yüksek kalite, kurulu, önce çevrim dışı.
   - Ayarlar'da hız (0,6–1,4×), US/UK aksanı, cihazdaki seslerden seçim ve "Sesi dene" var.
   - Dinleme, Dikte, Shadowing ve kartlar bu ayarları kullanır.
   - Ses kalitesi cihazın TTS motoruna bağlıdır; uygulama ses dosyası indirmez.
7. **Hızlı kart ekleme ve widget:**
   - Başka uygulamada metin seçilip **Paylaş** (veya metin seçim menüsündeki "LexiFlow'a ekle") ile `ShareActivity` açılır.
     - Uzun metinde eklenecek kelimeye dokunulur; seçilen cümle örnek olarak saklanır.
     - Türkçe anlam sözlükten gelir, yoksa AI'dan alınır, o da yoksa kullanıcı yazar.
     - Kelime Kişisel Kelime Defteri'ne eklenir. Kart sözlük, kelime ailesi ve (sunucu bağlıysa) AI ile otomatik zenginleşir. Zaten kayıtlıysa geçmişi korunarak deftere eklenir.
     - Kullanıcı "Ekle"ye basmadan hiçbir şey kaydedilmez.
   - Ana ekran widget'ı: "GÜNÜN KELİMESİ" (her yerel gün yeni kelime), anlamı göster, başka kelime, ve tek dokunuşla **"▶ Tekrara başla · N kart"**.
8. **FSRS testleri:** `core/src/test/.../FsrsCalculationTest.kt` (14 test) yalnızca FSRS hesaplamasını sınar. Beklenen değerler FSRS-6 formüllerinden bağımsız olarak hesaplandı. Kapsam:
   - başlangıç zorluk/stabilite;
   - Again/Hard/Good/Easy sonrası zorluk ve stabilite;
   - 1–10 sınırları;
   - hatırlanabilirlik eğrisi;
   - hedef tutmaya göre aralık;
   - sonraki tekrar tarihi;
   - geç tekrar.

   Başka alanlara test eklenmedi.
- **Veritabanı:** v4 (`MIGRATION_3_4`) yalnızca iki yeni tablo ekler. Kartlar ve FSRS geçmişi değişmez.
- **Sürüm:** 1.8.0 (versionCode 14).

## 1.7.0 değişiklikleri — Akıllı Günlük Seans (Otomatik Mod)
- **Otomatik Mod kartı (Ana sayfanın en üstünde):** "Hesaplanan süre: X dk (N eski + M yeni kelime)" bilgisi, "Süreyi ayarla" kaydırıcısı (5–120 dk), "Sessiz ortam / konuşamam" anahtarı ve Başlat / Devam et düğmesi var.
- **Süre hesabı (`core/autopilot`):**
  - Birim süreler: çeviri kartları 10 sn; boşluk doldurma, bağlam, dinleme ve telaffuz 20 sn; cümle, çeviri, üretim, gramer, shadowing ve dikte 40 sn; hikâye ve film/müzik bağlamı 90 sn; speaking 150 sn.
  - Süre; zamanı gelen FSRS kartlarından, zayıf kelimelerden ve günlük yeni kelime hedefinden hesaplanır ve en fazla 90 dakikadır.
  - **Süre kısalınca:** Önce zamanı gelen ve zayıf kelimeler gelir, yeni kelimeler orantılı azalır, uzun üretim adımları yerine boşluk doldurma ve bağlam soruları seçilir.
  - **Süre uzayınca:** Kalan zaman kelime defteri, aktif üretim yolu, unutma riski hikâyesi ve speaking ile dolar.
- **5 aşama:**
  1. Kritik tekrar ve ısınma (%25): yeni kelimelere EN→TR, olgun kelimelere TR→EN.
  2. Yeni kelimeler ve ses (%15): zengin kart, dinleme, telaffuz.
  3. Bağlamsal kavrama (%20): hikâye veya bağlam, ardından bağlam meydan okuması ve boşluk doldurma.
  4. Pasiften aktife (%25): cümle oluşturma, çeviri, dikte, gramer.
  5. Final sentezi (%15): speaking ve kelime üretimi; seans günlük görevi tamamlar.
- **Olgunluğa göre alıştırma:** FSRS stability 3 günün altındaysa tanıma, 21 günden sonra üretim alıştırmaları seçilir.
- **Örtük puanlama:** Seansta Again/Hard/Good/Easy düğmesi yok.
  - Yanlış = Again; ipucuyla, ikinci denemede veya geç doğru = Hard; hızlı ve ipucusuz doğru = Easy; diğer doğrular = Good.
  - Bir kart seans başına en fazla bir kez FSRS'e işlenir; erken tekrar yapılmaz.
  - Yanlış yapılan kelime Zayıf Kelimeler'e girer.
- **Sessiz mod:** AI Speaking → Yazılı Speaking, Telaffuz → Dikte, Shadowing → Kelime Üretimi.
- **Kilit ekranı:** Seansta yanlış yapılan kelimeler ve gramer noktaları o gün önce sorulur. Yeni "Hangi cümle doğru?" gramer sorusu var; bildirimde A/B ile cevaplanır.
- **Kalıp (collocation) desteği:** Örnek cümlede kelime edatıyla geçiyorsa ("rely on") boşluk kalıbın tamamını ister. Seçenekli soruda yanlış edatlı biçimler çeldirici olur. Cümle kurmada kalıp denetlenir.
- **Kart birleştirme:** AI Speaking ve Yazılı Speaking tek "Speaking Koçu" kartında, ses/yazı içeride seçilir. TR→EN ve EN→TR tek "Çeviri Kartları" kartında, yön içeride seçilir.
- **Veritabanı:** v3'te `priority_items` tablosu eklendi; geçiş testi var.
- **Sürüm:** 1.7.0 (versionCode 13). Bu sayı, kullanıcının 1.6.0 (versionCode 12) APK'sının üstünde.

## 1.6.0 (kaynağa geri taşındı)
1.6.0 APK'sı bu repoda olmayan bir koddan derlenmişti. 1.5.1 ve 1.6.0 APK'ları çözümlenip gerçek farklar koda taşındı:
- Mürekkep mavisi ve krem renkli tema; Inter ve Literata yazı tipleri.
- Ekranlarda sadeleştirilmiş yeni tasarım.
- Desteler ekranında "Yeni deste" ve hazır set satırı.
- Kullanıcının Anki desteleri (`assets/anki`, "Anki destem" kategorisi).

## 1.5.1 değişiklikleri
- **Hazır setler seviyeye göre gruplu:** Kütüphane A1 → C2 bölümlerinden oluşuyor. Her konu seti, kelimelerinin çoğunluğunun seviyesine göre ilgili bölüme yerleşiyor ve kategorisi etiket olarak görünüyor.
- **Seviye paketleri:** Her seviyenin başında, setlerdeki ve LexiFlow sözlüğündeki o seviyedeki bütün kelimeleri içeren bir paket var (ör. "A1 Seviye Paketi"). Paket tek dokunuşla deste olarak eklenir. Başka destede zaten olan kelimeler atlanır.

## 1.5.0 değişiklikleri
- **LexiFlow sözlüğü (`assets/lexicon`):** Yaklaşık 2.400 genel İngilizce kelime için özgün içerik eklendi: seviye (A1–C2), kelime türü, okunuş, İngilizce açıklama, en az 3 örnek cümle ve Türkçe notlar. Kelime listesi, kullanıcının paylaştığı İngilizce–Türkçe Anki destesindeki kelimelerden seçildi; destedeki Türkçe anlamlar uygulamaya konmadı.
- **İçe aktarma zenginleştirmesi:** Yalnızca "kelime = anlam" içeren desteler içe aktarılırken LexiFlow'un bildiği her kelimeye açıklama, örnek cümleler, seviye ve notlar eklenir. Öğrencinin kendi Türkçe anlamı korunur. Böylece boşluk doldurma, cümle dinleme ve anlamdan kelimeye tekrar kartı bu kelimelerde de çalışır.
- **Seviyeye göre gruplama:**
  - İçe aktarırken "Seviyeye göre ayrı destelere böl" seçeneği var (varsayılan açık); örneğin "Deste · A1", "Deste · B2".
  - Deste ekranında kelimeler seviye başlıkları altında listelenir.
  - Daha önce içe aktarılmış desteler için "✨ Zenginleştir" ve "📊 Seviyeye göre böl" düğmeleri var. Kartlar tekrar geçmişiyle birlikte taşınır.
- **Tekrar kontrolü:** Kelimenin bütün anlamları zaten kayıtlıysa satır tekrar sayılır. Böylece içe aktarma artık yerleşik desteye fazladan anlam eklemiyor.

## 1.4.1 değişiklikleri
- **FSRS tekrarı ve Flashcards:**
  - Kartın ön yüzünde Türkçe anlam, kelimesi gizlenmiş İngilizce açıklama, kelime türü ve kelimesi boşluk bırakılmış örnek cümle var.
  - Kelime, okunuşu ve sesi "Cevabı göster"den sonra görünür.
  - Eski görünüm (önce kelime) Ayarlar → Öğrenme bölümünden seçilebilir.
- **İpuçları yalnızca istenince:**
  - Tüm alıştırmalarda (Türkçeden İngilizceye, boşluk doldurma, cümle kurma, çeviri, gramer) ipuçları kendiliğinden görünmüyor. 💡 düğmesine her basışta bir ipucu açılıyor, ilk harf en sonda geliyor.
  - Soruyu tekrar eden "Anlam" ipucu Türkçeden İngilizceye sorusundan kaldırıldı.
  - Gramer Antrenörü'ndeki "Odak" (cevabı ele veren kalıp) da ipucuna taşındı.
  - Film/müzik bağlamı ekranında hedef kelime artık soru açılmadan gösterilmiyor; anlam ve ilk harf ipucu olarak açılıyor.

## 1.4.0 değişiklikleri
- **Hazır kelime setleri (Desteler → 📚 Hazır setler):** LexiFlow için yazılmış 12 özgün set var: toplam 360 kelime ve 1.671 örnek cümle. Her kelimede Türkçe anlam, İngilizce açıklama, örnek cümleler ve Türklerin sık yaptığı hatalar bulunur.
  - Seviye setleri: A1–A2 Günlük Hayat, B1 Fiiller, B2 Sıfatlar
  - Sınav setleri: YDS/YÖKDİL Akademik Fiiller, YDS Bağlaçlar, YÖKDİL Sağlık / Fen / Sosyal
  - Kalıp setleri: Phrasal Verb'ler, Collocations
  - Konu setleri: İş İngilizcesi, Seyahat
- **Setlerin kullanımı:**
  - Setler önizlenebilir. "Desteye ekle" ile ayrı deste olarak FSRS tekrarına girer.
  - Zaten var olan aynı anlamlı kelimeler atlanır.
  - Deste silinip set yeniden eklenebilir.
- **Yeni deste:** Desteler ekranından boş bir deste oluşturup kendi kelimelerini ekleyebilirsin.
- **İçerik kaynağı:** Konular Quizlet ve AnkiWeb'deki popüler set türlerine bakılarak seçildi. İçerik kopyalanmadı; ayrıntı CONTENT_SOURCES_AND_LICENSING.md dosyasında.

## 1.3.0 değişiklikleri
- **Kelime sorusu (kilit ekranı / uygulama içi):**
  - "💡 İpucu göster" düğmesi cevabı söylemeden yardım eder: kelime gizlenmiş İngilizce anlamı, kelime türü ve İngilizce cevaplarda ilk harf ile harf sayısını gösterir.
  - "🤷 Bilmiyorum, cevabı göster" seçeneği cevabı ve açıklamayı gösterir. Tekrar zamanı gelmiş kartta bu, FSRS'te "Again" sayılır.
  - Doğru cevaptan sonra "Bilmiyorum, tahmin ettim" değerlendirmesi de var.
- **Alıştırmalar:**
  - Seçmeli ve yazmalı sorularda "Bilmiyorum" düğmesi var.
  - Ekranda görünen örnek cümle "🗣 Cümleyi dinle" ile seslendirilebilir. Sonuç kartında da cümle dinlenebilir.
- **Gramer Antrenörü:** Her cümlede hatanın ne olduğu ve nedeni gösterilir. Örnek: "does mistakes → makes mistakes; hata için do değil make kullanılır…". Açıklama, öğrenci cümlelerini kontrol eden kuralın kendisinden gelir.
- **Cümle değerlendirme daha sıkı:**
  - İngilizce kelimeye benzemeyen ifadeler ("bdnd", "asdfgh", küçük harfli Türkçe kelimeler) artık gramer, doğallık ve çeşitlilik puanı almıyor.
  - Hedef uzunluğun altındaki cevapların bu puanları kısalıkla orantılı düşürülüyor.
  - Türkçedeki gibi fiili sona koyan "Reluctant man he is." tipi kelime sırası hatası bulunup "He is a reluctant man." önerisiyle açıklanıyor.
- **Sesli cevap:**
  - "Durdur"a basınca dinleme gerçekten duruyor ve o ana kadar söylenen metin alınıyor. Önceden tanıyıcı sonuç gelmeden kapatılıyordu ve ekran "Dinliyorum…" durumunda kalıyordu.
  - Cihazda çevrim dışı İngilizce modeli yoksa tanıma bir kez çevrim içi hizmetle yeniden deneniyor (ayarlardaki açıklamaya uygun).
  - Hizmet hiç yanıt vermezse anlaşılır bir hata mesajı çıkıyor.

## 1.2.1 değişiklikleri
- **Anki alan eşleştirmesi:** Alan adları bir şey söylemediğinde (ör. "Text", "Field 2") ilk alan artık körü körüne kelime sayılmıyor. "C2.06.026" gibi kod/numara sütunları yok sayılıyor; kelime, Türkçe anlam ve örnek cümle sütunları içeriğe bakılarak seçiliyor. Türkçe sütun yoksa İngilizce açıklama anlam olarak kullanılıyor. Önizleme ekranında her sütun yine elle değiştirilebilir.
- **Deste silme:** Desteler ekranındaki çöp kutusu ve deste ekranının üst çubuğu ile, onay sorulduktan sonra deste; kelimeleri, kartları ve tekrar geçmişiyle birlikte silinir. Hazır LexiFlow destesi silinirse uygulama yeniden açıldığında geri yüklenir.
- Daha önce yanlış aktarılmış bir deste için: desteyi silip .apkg dosyasını yeniden içe aktar.

## APK
| Dosya | Açıklama |
|---|---|
| `LexiFlow/dist/LexiFlow-1.8.0.apk` | **Kurulacak dosya.** Release derlemesi, ~18 MB, minSdk 26 (Android 8.0+), targetSdk 35. Yerel Android **debug** sertifikasıyla imzalı; `apksigner verify` başarılı. Test amaçlı yan yüklemedir, Play Store'a yüklenemez. |
| `LexiFlow/app/build/outputs/apk/debug/app-debug.apk` | Debug derlemesi (~20 MB), aynı kod. |

Bu dosyalar bu oturumda gerçekten üretildi. Komutlar: `./gradlew :app:assembleRelease`, `./gradlew :app:assembleDebug`.

## Tamamlanma matrisi (özet)
| Alan | Durum |
|---|---|
| FSRS-6 zamanlayıcı, tekrar ekranı, review log | IMPLEMENTED |
| Aktif kullanım planlayıcısı, 7 beceri, "tanıyorum ama kullanamıyorum" | IMPLEMENTED |
| Kilit ekranı soruları (4 tip, doğrudan cevap, öz değerlendirme, spam koruması, gizlilik) | IMPLEMENTED (cihazda doğrulanmadı) |
| Yerleşik içerik: 40 kelime, 91 anlam, 444 özgün örnek (kelime başı ≥10) | IMPLEMENTED |
| CSV/TSV/Anki düz metin içe aktarma | IMPLEMENTED |
| .apkg içe aktarma | PARTIAL (eski SQLite formatı + revlog; anki21b ve medya yok) |
| Film/dizi/müzik bağlamı (31 lisans doğrulanmış öğe + kendi altyazın) | IMPLEMENTED |
| Hikâyeler + unutma eğrisi hedefleri + sorular | IMPLEMENTED (AI hikâye: PARTIAL) |
| Konuşma koçu (sesli/yazılı) | PARTIAL (AI yolu gerçek modelle denenmedi) |
| Telaffuz (kelime düzeyi anlaşılırlık, minimal çiftler, shadowing) | PARTIAL (fonem/vurgu ölçümü yok) |
| Kelime yarışması | Bot: IMPLEMENTED · Online: PARTIAL (sunucu test edildi, istemci canlı sunucuya karşı denenmedi) |
| Oyunlaştırma ve ilerleme paneli | IMPLEMENTED |
| 24 mod (+7 ek) | IMPLEMENTED |
| Testler | core 41 + app 35 + backend 22 (+1 PostgreSQL) — hepsi geçti |
| Gerçek cihaz/emülatör testi | BLOCKED (KVM yok) |
| CodeRabbit | BLOCKED (kurulu değil); bağımsız manuel inceleme yapıldı, 15+2 bulgu düzeltildi |

## Kurulum ve çalıştırma
**Telefona kurulum:**
1. `dist/LexiFlow-1.8.0.apk` dosyasını telefona aktar.
2. Dosyayı aç → bilinmeyen kaynaklara izin ver → Yükle.
3. İlk açılışta seviyeni ve hedefini seç. Kilit ekranı sorularını açarsan bildirim iznini ver.
4. **Kilit ekranı** → "Şimdi test sorusu gönder" ile ekranı kilitleyip deneyebilirsin.

**Geliştirici:**
```bash
cd LexiFlow
./gradlew :core:test :app:testDebugUnitTest :backend:test
./gradlew :app:assembleRelease
```

**Sunucu (isteğe bağlı):**
```bash
cd LexiFlow/backend && cp .env.example .env   # JWT_SECRET, ANTHROPIC_API_KEY, AI_PROVIDER=anthropic
docker compose up -d                          # PostgreSQL 16 + sunucu (8080)
```
Ardından uygulamada: **Ayarlar → Sunucu → `https://sunucu-adresin` → Bağlan.** Telefonun sunucuya HTTPS ile erişmesi gerekir; geliştirme için Android emülatöründe `http://10.0.2.2:8080` kullanılabilir.

## Yapılandırma
| Nerede | Ne | Not |
|---|---|---|
| `backend/.env` | `JWT_SECRET` (≥32 karakter), `DATABASE_URL`/`DB_USER`/`DB_PASSWORD`, `AI_PROVIDER`, `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL` (varsayılan `claude-opus-5-5`), `RATE_LIMIT_AI_PER_MINUTE` | Gerçek anahtar repoya konmaz |
| `keystore.properties` | Play için release imzası | Yoksa debug anahtarıyla imzalanır |
| Uygulama Ayarlar ekranı | Seviye, günlük hedef, yeni kart/gün, FSRS hatırlama oranı, kilit ekranı (sayı, aralık, sessiz saat, havuz, zorluk, Türkçe, gizleme), aksan, çevrim dışı tanıma tercihi, gizlilik ve saklama süreleri, sunucu | |

## Dış gereksinimler (kullanıcının sağlaması gerekenler)
1. **Anthropic API anahtarı** ve sunucu barındırma (AI hikâye, AI konuşma, zenginleştirme). Ücretli servis olduğu için hesap açılmadı.
2. **HTTPS ile erişilebilir sunucu adresi** (online yarışma ve AI).
3. **Play Store dağıtımı için release keystore.**
4. **İsteğe bağlı:** Telefonda çevrim dışı İngilizce konuşma tanıma dil paketi (Google uygulaması / sistem ayarları).

## Bilinen kısıtlar
- **Cihazda denenmedi:** Gerçek cihaz/emülatör testi yapılamadı. Android'e özgü davranışlar Robolectric ile JVM'de test edildi.
- **Telaffuz:** Fonem düzeyi puanlama ve vurgu ölçümü yok; dürüst kapsam PRONUNCIATION.md'de.
- **İçe aktarma:**
  - `.apkg`: yalnızca "Support older Anki versions" ile dışa aktarılmış paketler okunur; medya aktarılmaz.
  - İçe aktarılan kelimeler 10+ örnek için AI zenginleştirmeye (sunucu) ihtiyaç duyar.
- **FSRS:** Parametre optimizasyonu yok.
- **Online yarışma:** Bağlantı koparsa yeniden bağlanma kullanıcı dokunuşuyla yapılır; otomatik değil.
- **Senkronizasyon:** Bulut senkronu yok. `sync_queue` kayıtlarını gönderen worker yok (moderasyon bildirimi WebSocket üzerinden de gönderilir).
- **Arayüz:** Yalnızca Türkçe; çoğu metin kaynak dosyası yerine kod içinde (lint HardcodedText uyarıları).
- **Release derlemesi:** R8 küçültme kapalı (cihaz doğrulaması yapılmadan açılmadı).
- **Bağımlılıklar:** Bu ortamda uyumluluğu kanıtlanmış kararlı sürümler kullanıldı; lint daha yeni sürümler bildiriyor.

## Cihazda doğrulanması gerekenler (manuel test listesi)
1. Android 8, 12, 13, 14 ve 15'te kurulum ve ilk açılış (bildirim izni akışı).
2. **Kilit ekranı:**
   - Kilitli telefonda bildirimin görünmesi
   - Seçenek düğmesinin kilit açmadan çalışması
   - Sonuç bildirimi ve öz değerlendirme düğmeleri
   - "Gizle" ayarında içeriğin saklanması
   - Sessiz saatlerde gelmemesi
3. Cümle kurma sorusunda satır içi yanıt (kilit açma isteyebilir) ve bildirime dokunarak uygulamada açılma.
4. Widget ekleme, "Anlamı göster" ve "Sonraki".
5. Mikrofon izni, konuşma tanıma (çevrim içi/dışı), gürültülü ortam, farklı aksanlar; TTS UK/US sesleri.
6. Gerçek Anki dışa aktarımları: .txt ve .apkg (eski format), tekrar geçmişinin korunduğunun kontrolü.
7. Sunucu bağlıyken: AI hikâye, AI konuşma, zenginleştirme, iki telefonla online yarışma, kopma ve geri dönme.
