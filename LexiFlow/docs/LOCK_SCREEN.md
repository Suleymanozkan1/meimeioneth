# Kilit ekranında İngilizce öğrenme

## Ne yapılıyor
- Bildirim kanalı `lock_questions`: önem DEFAULT, sessiz, titreşimsiz, `VISIBILITY_PUBLIC`.
- Kullanıcı isterse soru kilit ekranında gizlenir: `VISIBILITY_PRIVATE` + "Yeni bir kelime sorusu var" yazan genel sürüm.
- **Soru tipleri dönüşümlü** (`QuestionRotation`):
  - İngilizceden Türkçeye
  - Türkçeden İngilizceye ("Ertelemek — İngilizcesi nedir?")
  - Boşluk doldurma ("I had to ______ the meeting until Friday.")
  - Aktif cümle üretimi ("Reliable kelimesiyle kendi cümleni kur.")

  Hatırlama veya üretimi zayıf kelimelerde TR→EN ve üretim daha sık gelir.
- **Kilit ekranından doğrudan cevap:** 3 seçenek düğmesi broadcast `PendingIntent` kullanır. Android, `setAuthenticationRequired(false)` varsayılanıyla (API 31+) bu düğmelere kilit açmadan dokunulmasına izin verir.
- **Cevaptan sonra:** Aynı bildirim sonuçla güncellenir: doğru cevap, İngilizce açıklama, örnek cümle (+ Türkçe), kısa not. Ardından düğmeler gelir: **Biliyorum / Zorlandım / Tanıyorum ama kullanamıyorum**.
- **Cümle üretimi:** `RemoteInput` (satır içi yanıt) ile yazılır. Çoğu cihaz güvenli kilitte yazı girişi için kilidi açmayı ister. Bu Android'in kararıdır, aşılmaz. Bildirime dokunmak soruyu uygulamada açar (`lexiflow://lockquestion/{id}`).
- **Kayıt:**
  - Her cevap `practice_logs` (kaynak LOCKSCREEN) ve `lockscreen_questions` tablolarına yazılır.
  - FSRS'e yalnızca kart zamanı geldiyse yazılır (FSRS.md).
  - Yanlış veya "kullanamıyorum" cevabı aktif tekrar listesine girer.
- **Ek yüzeyler:**
  - Ana ekran widget'ı (anlamı göster / sonraki kelime).
  - Uzun basma kısayolları: Tekrar, Kelime sorusu, Konuş.

## Spam koruması (kullanıcı ayarlı)
- Açma/kapama
- Günlük soru sayısı (1–20, varsayılan 8)
- Sorular arası en kısa aralık (15–240 dk, varsayılan 90)
- Sessiz saatler (gece yarısını geçebilir, varsayılan 22–08)
- Kelime havuzu (öğrendiklerim / karışık)
- Zorluk
- Türkçe göster
- Güvenli kilitte gizle

Worker 15 dakikada bir uyanır; kapı kapalıysa hiçbir şey göstermez. Yeni soru eskisinin yerine geçer (tek bildirim kimliği).

## Yapılmayanlar (bilinçli)
- Sistem kilit ekranını değiştirmek, `SYSTEM_ALERT_WINDOW` ile üstte çizmek, `showWhenLocked` ile kilit üstünde etkinlik açmak, güvenli kilidi atlamak.
- Kilit ekranı widget'ı: Android 5–?'deki keyguard widget'ları kaldırıldı. Android 15'in yeni kilit ekranı widget'ları yalnızca belirli tabletlerde ve "communal" yüzeyde var. Bu yüzden telefonlarda kilit ekranı yüzeyi bildirimlerdir. Widget XML'i `keyguard` kategorisini bildirir; destekleyen cihazlarda görünür.

## Sürüm notları
| Android | Davranış |
|---|---|
| 8–12 (API 26–32) | Bildirim izni gerekmez. Kanal ayarları kullanıcıda. Düğmeler kilit ekranından çalışır. |
| 13+ (API 33+) | `POST_NOTIFICATIONS` izni istenir (açılışta / anahtarı açınca). Reddedilirse uygulama uyarı gösterir. |
| 12+ | Bildirim aksiyonları kimlik doğrulama istemeden çalışır (varsayılan). Satır içi yazı girişi cihaza göre kilit açmayı isteyebilir. |

## Test durumu
- **Robolectric testleriyle doğrulandı (JVM):**
  - Bildirimin 3 cevap düğmesi
  - Gizli sürüm (`VISIBILITY_PRIVATE` + publicVersion)
  - Kanal
  - Doğru seçeneğe dokunma yayını → kayıt → sonuç bildiriminde 3 öz-değerlendirme düğmesi
  - Kapı mantığı: sessiz saat, günlük sınır, aralık
- **Gerçek cihazda doğrulanmadı.** Bu ortamda emülatör çalışmıyor (KVM yok). Kilit ekranında görünürlük ve satır içi yanıt davranışı cihazda kontrol edilmeli (FINAL_IMPLEMENTATION_REPORT.md → manuel test listesi).
