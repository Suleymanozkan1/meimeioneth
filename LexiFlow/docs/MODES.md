# Öğrenme modları

Her mod ayrı bir öğrenme amacına hizmet eder, gerçek veriyle çalışır ve sonucu kaydeder. Boş durumda (ör. zayıf kelime yok) neden boş olduğunu ve ne yapılacağını söyler.

| # | Mod | Amaç | Ekran / kod | Kayıt |
|---|---|---|---|---|
| 1 | Flashcards | Serbest kart çevirme; zamanlamayı etkilemez | ReviewScreen(free) | — |
| 2 | FSRS Tekrarı | Zamanı gelen kartlar, Again/Hard/Good/Easy + aralık önizleme | ReviewScreen | review_logs |
| 3 | Türkçeden İngilizceye | Aktif hatırlama, kademeli ipucu | DrillScreen TR_TO_EN | skill RECALL |
| 4 | İngilizceden Türkçeye | Anlamı yardımsız hatırlama | DrillScreen EN_TO_TR | MEANING_RECALL |
| 5 | Boşluk doldurma | Seçenekli → ipuçlu → ipucusuz; boşluk cümlede farklı yerlerde | DrillScreen CLOZE | CONTEXT |
| 6 | Cümle oluşturma | Kelimeleri doğru söz dizimine dizme | SENTENCE_BUILDER | CONTEXT |
| 7 | Kelime üretimi | İpucusuz kendi cümlesi; sesli de olabilir | WORD_PRODUCTION | PRODUCTION / SPEAKING |
| 8 | Günlük kelimeler | Bugünün yeni kelimeleri zengin kartlarıyla | DailyWordsScreen | — (tekrarda FSRS'e girer) |
| 9 | Kilit ekranı soruları | Dönüşümlü 4 tip soru; ayarlar, test, geçmiş | LockScreenScreen, LockQuestionScreen, bildirim | practice_logs, lockscreen_questions, (due ise) review_logs |
| 10 | Film/dizi bağlamı | Kamu malı film anlatımları, özgün sahneler, kendi altyazın | CultureScreen(SCREEN) | CONTEXT / PRODUCTION |
| 11 | Müzik bağlamı | Kamu malı şarkılar, özgün dizeler | CultureScreen(MUSIC) | CONTEXT / PRODUCTION |
| 12 | AI hikâyeleri | Tema/seviye/uzunluk/gramer/zorluk seçimli hikâye | StoriesScreen | stories, story_words |
| 13 | Unutma riski hikâyeleri | FSRS R değeri düşen/zayıf kelimelerle | StoriesScreen(fc) | aynı |
| 14 | Hikâye soruları | Bağlamda anlam, boşluk, TR→EN, kendi cümlesi, yeniden anlatma | StoryReaderScreen | story_attempts + beceriler |
| 15 | Gramer | Türk öğrencilerin sık hataları: bul ve düzelt (28 madde) | DrillScreen GRAMMAR | XP |
| 16 | AI speaking | Sesli koç; hedef kelimeleri doğal kullanır | SpeakingScreen(voice) | speaking_* + SPEAKING |
| 17 | Yazılı speaking | Aynı koç, yazarak | SpeakingScreen(text) | + PRODUCTION |
| 18 | Telaffuz laboratuvarı | Kelime/cümle anlaşılırlığı + minimal çiftler (dinle-ayırt et, söyle) | PronunciationScreen | pronunciation_attempts |
| 19 | Dinleme | Konuşmadaki kelimeyi tanıma | DrillScreen LISTENING | MEANING_RECALL |
| 20 | Shadowing | Dinle-tekrar et; anlaşılırlık + hız | ShadowingScreen | PRONUNCIATION |
| 21 | Online kelime yarışması | 5 kelimeyle en iyi cümle/hikâye; bot antrenmanı ayrıca | BattleScreen | competitions |
| 22 | Günlük görev | Günün 5 kelimesi; bir kez tamamlanır (+30 XP) | DAILY_CHALLENGE | daily_challenges |
| 23 | Zayıf kelimeler | FSRS unutma + düşük aktif ustalık + işaretliler | WEAK_WORDS | beceriler |
| 24 | İlerleme paneli | Seri, XP, grafikler, beceriler, görevler, rozetler, risk listesi | ProgressScreen | — |
| + | Çeviri pratiği | TR cümle → EN, hedef kelimeyle | TRANSLATION | PRODUCTION |
| + | Bağlam meydan okuması | İngilizce tanıma uyan kelime | CONTEXT_CHALLENGE | CONTEXT |
| + | Dikte | Duyulanı eksiksiz yazma | DICTATION | CONTEXT |
| + | Aktif üretim yolu | "Kullanamıyorum" kelimeleri için 5 adımlı merdiven | ACTIVE_PATH | beceriler |
| + | Karışık tekrar | Tür karıştırma (interleaving) | MIXED_REVIEW | beceriler |
| + | Sınav pratiği | 20 soru, 10 dk, ipucusuz | EXAM | beceriler |
| + | Kişisel kelime defteri | Kendi kelimelerin ve cümlelerin | WordbookScreen | words |
