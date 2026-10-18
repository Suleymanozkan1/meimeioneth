# İçerik kaynakları ve lisanslar

## 1. Yerleşik kelime destesi — LexiFlow özgün içerik
- **Dosyalar:** `app/src/main/assets/content/words_A..D.json`. İçerik 40 kelime, 91 anlam (sense) ve **444 örnek cümleden** oluşur. Her kelimede en az 10 örnek var (min 10, çoğunlukla 11–13).
- **Kaynak:** Bu proje için özgün olarak yazıldı. Hiçbir sözlükten, Anki paylaşılan destesinden veya web sitesinden kopyalanmadı.
- **Lisans:** Proje ile birlikte dağıtılabilir (proje sahibinin seçeceği lisans altında). Üçüncü taraf hakkı yoktur.
- **Kalite kontrol:**
  - Şema doğrulaması: her `answer` cümlede birebir geçer; bağlam etiketleri izinli listededir; uzunluk 6–20 kelimedir.
  - Uygulama ilk açılışta geçersiz örnekleri reddeder (hedef kelime cümlede yoksa).
  - Örnekler bağlamlara dağıtıldı: günlük, arkadaş arası, iş, akademik, farklı anlam, gramer yapısı, collocation, phrasal/deyim (yalnızca gerçekse), soru, olumsuz, konuşma dili.
  - Var olmayan anlam veya deyim uydurulmadı; uygun olmayan bağlam zorlanmadı.
  - Boşluğa başka bir kelime de uyuyorsa `acceptedAlternatives` alanına yazıldı; uygulama bu cevapları doğru kabul eder.
- **Bilinen sınırlar:**
  - CEFR seviyeleri ve bazı alternatif cevaplar yazarların tahminidir.
  - IPA elle yazıldı. Yazım İngiliz İngilizcesi ağırlıklı (realise, harbour).
  - Çok türlü kelimelerde (approach, challenge, influence, regret, struggle) `pos` tek değer. Diğer tür, anlam etiketinde "(noun)/(verb)" olarak belirtildi.

## 2. Kullanıcının içe aktardığı içerik
- Kullanıcının kendi Anki/CSV dosyaları **yalnızca cihazda** saklanır, hiçbir yere gönderilmez ve yeniden dağıtılmaz.
- Uygulama Anki'nin paylaşılan destelerini **indirmez veya kazımaz**.
- İçe aktarma ekranı "yalnızca kendi destelerini veya kullanma hakkın olanları aktar" uyarısını gösterir.
- Yerleşik içerik (`source = BUILTIN`) ile kullanıcı içeriği (`IMPORT`, `USER`, `AI`) veritabanında ayrı etiketlidir.

## 3. AI tarafından üretilen içerik
- AI yalnızca kullanıcı sunucuya bağlanıp AI'ı açarsa kullanılır.
- Üretilen örnekler `origin = AI` ile saklanır.
- AI çıktısı JSON şemasıyla hem sunucuda hem cihazda doğrulanır (`AiValidation`): hedef kelime cümlede geçmeli; en az 10 farklı örnek olmalı; MCQ cevap indeksi geçerli olmalı. Bozuk çıktı reddedilir ve yerel içerik kullanılır.
- Aynı cümle tekrar eklenmez.

## 4. Film / dizi / müzik bağlamı (`cultural.json`, 31 öğe)
Kurallar:
- **Modern şarkı sözü veya film/dizi repliği yoktur.**
- **Kamu malı şarkılar:** En fazla 2 satır alıntı. Satırlar Wikipedia ham metniyle birebir karşılaştırılarak doğrulandı.
- **Kamu malı filmler:** Yalnızca kendi sözlerimizle anlatım; alıntı yok. Kamu malı statüsünün ülkeye göre değişebileceği not edildi.
- **Özgün sahneler:** Tür tarzında yazılmış LexiFlow metinleridir. Gerçek bir esere veya karaktere atıf yoktur; "gerçek bir eserden değil" diye etiketlidir.
- **Çıkarılanlar:**
  - *Take Me Out to the Ball Game* (yazar 1959'da öldü; ABD'de kamu malı ama "ölümden 70 yıl" kuralını geçmiyor)
  - *Clementine* (orijinal metin doğrulanamadı)
  - *Oh! Susanna* (1848 orijinal metni ırkçı ifade içeriyor)
- **Kullanıcı altyazıları:** Kullanıcı kendi .srt/.txt dosyasını ekleyebilir. Yalnızca destesindeki kelimeleri içeren ≤200 karakterlik cümleler cihazda saklanır (`USER_PROVIDED_PERSONAL_USE`) ve hiçbir yere gönderilmez.

| Tür | Başlık | Yaratıcı | Yıl | Lisans durumu | Alıntı | Hedef kelime | Kaynak |
|---|---|---|---|---|---|---|---|
| MUSIC | Home on the Range | Brewster M. Higley (lyrics) | 1873 | PUBLIC_DOMAIN | evet (≤2 satır) | seldom | https://en.wikipedia.org/wiki/Home_on_the_Range |
| MUSIC | Home on the Range | Brewster M. Higley (lyrics) | 1873 | PUBLIC_DOMAIN | evet (≤2 satır) | exchange | https://en.wikipedia.org/wiki/Home_on_the_Range |
| MUSIC | Amazing Grace | John Newton (lyrics) | 1779 | PUBLIC_DOMAIN | evet (≤2 satır) | wretch | https://en.wikipedia.org/wiki/Amazing_Grace |
| MUSIC | Amazing Grace | John Newton (lyrics) | 1779 | PUBLIC_DOMAIN | evet (≤2 satır) | thus far | https://en.wikipedia.org/wiki/Amazing_Grace |
| MUSIC | Daisy Bell (Bicycle Built for Two) | Harry Dacre | 1892 | PUBLIC_DOMAIN | evet (≤2 satır) | afford | https://en.wikipedia.org/wiki/Daisy_Bell |
| MUSIC | Daisy Bell (Bicycle Built for Two) | Harry Dacre | 1892 | PUBLIC_DOMAIN | evet (≤2 satır) | whether | https://en.wikipedia.org/wiki/Daisy_Bell |
| MUSIC | Oh Shenandoah | Traditional | — | PUBLIC_DOMAIN | evet (≤2 satır) | long | https://en.wikipedia.org/wiki/Oh_Shenandoah |
| MUSIC | My Bonnie Lies over the Ocean | Traditional (Scottish) | — | PUBLIC_DOMAIN | evet (≤2 satır) | bring back | https://en.wikipedia.org/wiki/My_Bonnie_Lies_over_the_Ocean |
| MUSIC | Row, Row, Row Your Boat | Traditional (first printed 1852; tune credited to Eliphalet Oram Lyte, 1881) | 1852 | PUBLIC_DOMAIN | evet (≤2 satır) | gently | https://en.wikipedia.org/wiki/Row,_Row,_Row_Your_Boat |
| FILM | Nosferatu | F. W. Murnau (director) | 1922 | PARAPHRASE | hayır | reluctant | https://en.wikipedia.org/wiki/Nosferatu |
| FILM | The General | Buster Keaton & Clyde Bruckman (directors) | 1926 | PARAPHRASE | hayır | assume | https://en.wikipedia.org/wiki/The_General_(1926_film) |
| FILM | His Girl Friday | Howard Hawks (director) | 1940 | PARAPHRASE | hayır | persuade | https://en.wikipedia.org/wiki/His_Girl_Friday |
| FILM | Night of the Living Dead | George A. Romero (director) | 1968 | PARAPHRASE | hayır | insist | https://en.wikipedia.org/wiki/Night_of_the_Living_Dead |
| ORIGINAL_SCENE | LexiFlow original: Sitcom-style kitchen scene | LexiFlow (original) | — | ORIGINAL_LEXIFLOW | hayır | run | — |
| ORIGINAL_SCENE | LexiFlow original: Crime-drama-style evidence room | LexiFlow (original) | — | ORIGINAL_LEXIFLOW | hayır | overlook | — |
| ORIGINAL_SCENE | LexiFlow original: Rom-com-style phone call | LexiFlow (original) | — | ORIGINAL_LEXIFLOW | hayır | postpone | — |
| ORIGINAL_SCENE | LexiFlow original: Pop-style chorus | LexiFlow (original) | — | ORIGINAL_LEXIFLOW | hayır | rely | — |
| ORIGINAL_SCENE | LexiFlow original: Rap-style verse | LexiFlow (original) | — | ORIGINAL_LEXIFLOW | hayır | struggle | — |
| ORIGINAL_SCENE | LexiFlow original: Musical-style opening number | LexiFlow (original) | — | ORIGINAL_LEXIFLOW | hayır | opportunity | — |
| ORIGINAL_SCENE | LexiFlow original: Workplace-sitcom-style supply closet | LexiFlow (original) | — | ORIGINAL_LEXIFLOW | hayır | efficient | — |
| ORIGINAL_SCENE | LexiFlow original: Medical-drama-style waiting room | LexiFlow (original) | — | ORIGINAL_LEXIFLOW | hayır | anxious | — |
| ORIGINAL_SCENE | LexiFlow original: Courtroom-drama-style cross-examination | LexiFlow (original) | — | ORIGINAL_LEXIFLOW | hayır | obvious | — |
| ORIGINAL_SCENE | LexiFlow original: Fantasy-film-style mountain hut | LexiFlow (original) | — | ORIGINAL_LEXIFLOW | hayır | deserve | — |
| ORIGINAL_SCENE | LexiFlow original: Sports-film-style locker room | LexiFlow (original) | — | ORIGINAL_LEXIFLOW | hayır | confident | — |
| ORIGINAL_SCENE | LexiFlow original: Heist-movie-style planning scene | LexiFlow (original) | — | ORIGINAL_LEXIFLOW | hayır | require | — |
| ORIGINAL_SCENE | LexiFlow original: Teen-drama-style school hallway | LexiFlow (original) | — | ORIGINAL_LEXIFLOW | hayır | regret | — |
| ORIGINAL_SCENE | LexiFlow original: Country-style ballad verse | LexiFlow (original) | — | ORIGINAL_LEXIFLOW | hayır | eventually | — |
| ORIGINAL_SCENE | LexiFlow original: Cooking-competition-style judging | LexiFlow (original) | — | ORIGINAL_LEXIFLOW | hayır | recommend | — |
| ORIGINAL_SCENE | LexiFlow original: Sci-fi-film-style cockpit | LexiFlow (original) | — | ORIGINAL_LEXIFLOW | hayır | approach | — |
| ORIGINAL_SCENE | LexiFlow original: Soul-style chorus | LexiFlow (original) | — | ORIGINAL_LEXIFLOW | hayır | appreciate | — |
| ORIGINAL_SCENE | LexiFlow original: Family-drama-style dinner table | LexiFlow (original) | — | ORIGINAL_LEXIFLOW | hayır | convince | — |

## 5. Kod ve algoritma kaynakları
- **FSRS-6 formülleri ve varsayılan parametreler:** open-spaced-repetition/fsrs-rs (BSD-3-Clause), fsrs4anki (MIT). Port bağımsız yazıldı. Ayrıntı: `REPOSITORIES.md`.
- **Anki .apkg formatı:** Yalnızca okuma (birlikte çalışabilirlik); Anki kodu (AGPL) kullanılmadı.
- **Bağımlılıklar:** AndroidX/Jetpack, Kotlin, kotlinx, OkHttp, Ktor, Logback, PostgreSQL JDBC ve HikariCP. Hepsi Apache-2.0 / MIT / BSD / EPL-LGPL (Logback) ve uyumlu lisanslardır.
