# Test raporu

**Ortam:** Linux bulut konteyneri, OpenJDK 21.0.12, Gradle 8.14.3, AGP 8.7.3, Kotlin 2.1.0, Android SDK 35 / build-tools 35.0.0, Robolectric 4.14.1 (Android API 34 ile).
**Emülatör / gerçek cihaz:** **BLOCKED.** Konteynerde `/dev/kvm` yok, Android emülatörü çalıştırılamadı. Android'e özgü davranışlar (Room, bildirimler, BroadcastReceiver, Compose UI, WorkManager) bu yüzden **Robolectric ile JVM üzerinde** test edildi.

## Çalıştırılan komutlar ve sonuçlar
| Komut | Sonuç |
|---|---|
| `./gradlew :core:test` | **35/35 geçti** |
| `./gradlew :app:testDebugUnitTest` | **31/31 geçti** |
| `./gradlew :backend:test` | **22 geçti, 1 atlandı** (PostgresStoreTest yalnızca DB URL verilince çalışır) |
| `LEXIFLOW_TEST_DATABASE_URL=jdbc:postgresql://localhost:55432/lexiflow_test ./gradlew :backend:test --tests '*PostgresStoreTest*'` (yerel PostgreSQL 16) | **1/1 geçti** |
| `./gradlew :app:lintDebug` | **0 hata**, 67 uyarı (54'ü "daha yeni bağımlılık sürümü var", 3 HardcodedText, 3 AGP sürümü, 2 ObsoleteSdkInt, 2 UnusedAttribute, 2 ModifierParameter, 1 UnusedResources) |
| `./gradlew :app:assembleDebug` | BAŞARILI → `app/build/outputs/apk/debug/app-debug.apk` (~20 MB) |
| `./gradlew :app:assembleRelease` | BAŞARILI → `app/build/outputs/apk/release/app-release.apk` (~13 MB, debug anahtarıyla imzalı; `apksigner verify` OK) |

Toplam: **88 test çalıştı, 88 geçti** (backend'de 1 test DB URL'siz çalıştırmada atlandı, ayrıca gerçek PostgreSQL ile çalıştırıldı ve geçti).

## Kapsam
### core (saf Kotlin)
- **FsrsTest (11):**
  - R(S,S)=0.9
  - fsrs-rs `next_interval_remains_fractional` referansı
  - ilk S/D
  - uzun ve kısa vadeli güncellemeler (bağımsız Python referansıyla)
  - öğrenme adımları, relearning ve lapses
  - Hard<Good<Easy sırası
  - fuzz aralığı
  - revlog yeniden oynatma, geçmiş yoksa NEW kalma
- **ActivePracticeTest (5):** ipucu azalması, ipuçlu cevabın daha az sayılması, başarısızlıkta 20 dk sonra tekrar, "kullanamıyorum" işaretinin ustalığı sınırlaması ve önceliği artırması, ipucu merdiveni.
- **LanguageTest (6) + GrammarDrillTest (1):**
  - "I made a party" → "I had a party"
  - doğal cümlelerde yanlış alarm olmaması
  - 10 yaygın hata düzeltmesi
  - çekim tanıma
  - kelime listesi tespiti ve yarışma puanı şeffaflığı
  - 28 gramer alıştırmasının hepsinin tespiti ve düzeltilmiş hâllerinde yanlış alarm olmaması
- **ImportAndMiscTest (12):**
  - CSV (BOM, tırnak, kaçış, başlık, hatalı satır raporu), Anki düz metin (#separator, HTML)
  - duplicate/yeni anlam ayrımı
  - seri (saat dilimi güvenli, gün bitmeden bozulmaz), seviye, haftalık görev
  - cevap kontrolü (alternatif/yazım/çekim)
  - boşluk doldurma ve örnek döndürme
  - telaffuz güvenilirliği ve minimal çift
  - hikâye planlayıcı, çevrim dışı hikâye şema doğrulaması
  - JSON çıkarma, protokol serileştirme

### app (Robolectric)
- **DatabaseAndMigrationTest (4):** anlam başına kart, geçersiz örneğin reddi, cascade silme + geri alma, **Room migration 1→2** (veri korunur, şema doğrulanır), askıya alınmış kart.
- **StudyFlowTest (7):**
  - 40 yerleşik kelime × ≥10 örnek, idempotent yükleme
  - **uçtan uca:** yükle → tekrar → log → sonraki tarih → yeni kart bütçesi → 30 gün sonra tekrar zamanı ve R<0.9
  - "kullanamıyorum" → aktif üretim kuyruğu (FSRS değişmez)
  - **15 tatbikat modunun hepsi** gerçek alıştırma üretiyor
  - üretim değerlendirmesi
  - günlük görev (gün içinde sabit, bir kez XP)
  - seri
- **LockScreenAndImportTest (6):**
  - kilit ekranı kapısı (sessiz saat, günlük sınır, aralık, kapalı)
  - kilit ekranı cevapları aynı geçmişlere yazılıyor; FSRS'e yalnızca due kartta yazılıyor
  - CSV içe aktarma raporu (duplicate, yeni anlam, hata, örnek uyarısı)
  - kodlama tespiti (UTF-8 / Windows-1254)
  - **.apkg içe aktarma** (sentetik eski format koleksiyon: alanlar, deste adı, HTML/[sound] temizliği, gerçek revlog'un FSRS ile yeniden oynatılması, geçmişsiz kartın NEW kalması)
  - yeni format .apkg'nin rehberli reddi
- **FeatureReposTest (7):**
  - çevrim dışı unutma eğrisi hikâyesinin FSRS'e dokunmaması
  - AI sunucusu 503 verince yerel hikâyeye düşme (MockWebServer)
  - bozuk AI JSON'unun reddi
  - çevrim dışı konuşma koçu
  - telaffuz denemesinin kaydı ve silinmesi
  - kültür içeriği (alıntıların hepsi kamu malı) ve altyazı içe aktarma
  - bot yarışması
- **NotificationTest (1):** kilit ekranı bildirimi
  - 3 cevap düğmesi
  - gizli sürüm (VISIBILITY_PRIVATE + publicVersion), kanal
  - yayın ile cevap → kayıt → sonuç bildiriminde Biliyorum/Zorlandım/Tanıyorum ama kullanamıyorum
- **UiFlowTest (6, Compose UI):**
  - ilk kurulum → ana ekran
  - kart tekrarı + "kullanamıyorum" + cevap kaydı
  - ayar değiştirme (günlük hedef)
  - içe aktarma ekranı
  - tatbikat cevabı puanlama
  - kilit ekranı ayarı + test sorusu + uygulama içi soru

### backend (Ktor testApplication)
- health, kayıt doğrulaması, token olmadan 401
- AI kapalıyken 503; geçersiz model çıktısında bir kez yeniden deneme, sonra 502; Anthropic sağlayıcısı sahte HTTP motoruyla
- iki istemcili WebSocket yarışma akışı, geç gönderim reddi, liderlik tablosu, oran sınırı
- PostgreSQL deposu

## FSRS referans betiği
Test değerleri, fsrs-rs `model_v6.rs` formülleriyle aynı şekilde yazılmış bağımsız Python betiğinden üretildi:
```python
w=[0.212,1.2931,2.3065,8.2956,6.4133,0.8334,3.0194,0.001,1.8722,0.1666,0.796,1.4835,0.0614,0.2629,1.6483,0.6014,1.8729,0.5425,0.0912,0.0658,0.1542]
decay=-w[20]; factor=0.9**(1/decay)-1
R=lambda t,s:(1+factor*round(t)/s)**decay
init_d=lambda g:w[4]-math.exp(w[5]*(g-1))+1
# succ(s,r,d,g) = s*(e^w8*(11-d)*s^-w9*(e^((1-r)w10)-1)*hard*easy+1)
# fail(s,r,d) = min(w11*d^-w12*((s+1)^w13-1)*e^((1-r)w14), s/e^(w17*w18))
```

## Hata kayıtları (bulunan → kök neden → düzeltme → tekrar test)
| # | Belirti | Kök neden | Düzeltme | Tekrar test |
|---|---|---|---|---|
| 1 | Ayrıştırma testinde "listen music everyday" yalnızca yarı düzeltildi | "everyday" kuralının eşleşmesi önceki kelimeyi de kapsıyordu; çakışan "listen to" kuralıyla birlikte düşüyordu | Kural lookbehind ile yalnızca "everyday"i kapsayacak şekilde değişti | LanguageTest geçti |
| 2 | Uygulama açılırken "Navigation graph has not been set" çökmesi (UI testi yakaladı) | Yönlendirme, Scaffold içindeki NavHost (alt kompozisyon) kurulmadan önce yapılıyordu | Başlangıç rotası ayarlar yüklendikten sonra belirleniyor; derin bağlantı efekti NavHost'tan sonra çalışıyor | UiFlowTest geçti |
| 3 | Veritabanı boşken yerleşik içerik yüklenmiyordu | Yükleme kararı yalnızca DataStore'daki sürüm numarasına bakıyordu | Karar veritabanının gerçek durumuna göre veriliyor; ayrıca Mutex ile eşzamanlı çift yükleme engellendi | StudyFlowTest + UiFlowTest geçti |
| 4 | Onboarding sonrası ana sayfaya geçilmiyordu (test ortamı) | Askıya alma sonrası navigasyon ana iş parçacığı dışında çalışabiliyordu | `onMain {}` yardımcısıyla navigasyon ana iş parçacığına alındı | UiFlowTest geçti |
| 5 | .apkg deste adı "Anki:Vocab" çıktı | Alt deste ayırıcı \u001f tek ":" ile değiştiriliyordu | Anki gösterimi "::" kullanıldı | LockScreenAndImportTest geçti |
| 6 | Migration testi şema dosyasını bulamadı | Şemalar test asset'lerine eklenmemişti | `app/schemas` debug asset kaynağına eklendi | Migration testi geçti |
| 7 | "My brother has twenty years old" tespit edilmedi | Kural yalnızca zamir özneyi kabul ediyordu | Her özne kabul ediliyor (has → is) | GrammarDrillTest geçti |
| 8 | Lint: BOM karakteri ve bildirim izni hataları | Kaynakta düz BOM karakteri; izin kontrolü ayrı fonksiyondaydı | `﻿` kaçışı; açıklamalı `@SuppressLint` (kontrol `canPost()` içinde) | lint 0 hata |

Kod incelemesi ile bulunan ve düzeltilen hatalar CODERABBIT_REPORT.md içindedir.

## Test edilmeyenler / cihazda doğrulanması gerekenler
- Gerçek cihaz veya emülatörde kurulum ve çalışma (BLOCKED: KVM yok).
- Kilit ekranında bildirimin görünürlüğü, düğmelerin kilit açmadan çalışması, satır içi yanıtın kilit açma istemesi (Android 8, 12, 13, 14, 15).
- `SpeechRecognizer` ile gerçek mikrofon, gürültü, farklı aksanlar; çevrim dışı dil paketi davranışı.
- TextToSpeech sesleri (UK/US).
- Widget'ın ana ekranda güncellenmesi.
- Gerçek Anki 2.1/23.x/25.x dışa aktarımlarıyla .apkg (sentetik koleksiyonla test edildi).
- Gerçek Claude API yanıtları (anahtar yok; sağlayıcı sahte HTTP ile test edildi).
- Release derlemesinde R8 küçültme (kapalı bırakıldı).
