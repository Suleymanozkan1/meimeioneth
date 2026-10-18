# Telaffuz laboratuvarı — yöntem ve dürüst kapsam

## Ne ölçüyoruz
1. **Kelime düzeyi anlaşılırlık:** Android `SpeechRecognizer` en fazla 5 aday döküm (n-best) ve varsa güven skorlarını verir. Hedef cümle ile en iyi aday kelime düzeyinde Levenshtein hizalamasından geçirilir. Her kelime şunlardan biri olarak işaretlenir:
   - Anlaşıldı
   - Belirsiz (yalnızca alternatif adaylarda duyuldu)
   - Farklı duyuldu (ne duyulduğu gösterilir)
   - Duyulmadı
2. **Minimal çift ipucu:** Tanıyıcı bilinen bir minimal çift eşini duyduysa sese özel ve açıklanabilir bir ipucu verilir. Liste Türk öğrenciler için 28 çift içerir; örnekler:
   - "ship" yerine "sheep" → /ɪ/–/iː/
   - think/sink → /θ/
   - very/berry → /v/–/b/
   - walk/work
3. **Konuşma hızı:** Süre ve kelime sayısından kelime/dakika hesaplanır. Akıcılık geri bildirimi buna dayanır.
4. **Güvenilirlik:**
   - Güven skoru yoksa veya <0.5 ise, ya da ses çok düşükse sonuç "Düşük güven" olur.
   - Ses yoksa veya kayıt <300 ms ise "Değerlendirilemedi" olur ve **puan verilmez**.

## Ne ölçmüyoruz (iddia edilmez)
- Fonem düzeyinde doğruluk (GOP), sesli/sessiz harf hatasının kesin yeri, hece/kelime vurgusu, tonlama.
- Bunlar ASR çıktısından güvenilir biçimde çıkarılamaz.
- Vurgu bilgisi kelime kartındaki IPA (ˈ) ve vurgu alanıyla, referans ses (TTS, ABD/İngiliz aksanı, yavaş mod) ile öğretilir.

## Neden SpeechRecognizer?
- APK'ya model gömmeden her cihazda çalışır.
- `EXTRA_PREFER_OFFLINE` ile cihazdaki model tercih edilir.
- Hizmet sağlayıcıya göre ses sunucuda işlenebilir. Bu durum Ayarlar'da ve konuşma ekranında açıkça yazılır.
- **LexiFlow ham sesi hiç kaydetmez.** Yalnızca döküm ve sonuç `pronunciation_attempts` tablosunda saklanır (süre ayarlı, silinebilir).

## Gelecek yol (plan)
- Çevrim dışı paket: sherpa-onnx streaming ASR (Apache-2.0).
- Fonem tanıyıcı (ör. wav2vec2-CTC fonem modeli, ONNX) + hedef IPA ile forced alignment → fonem düzeyi GOP puanı.
- Önkoşullar: Model lisansı, boyut ve cihaz performansı gerçek cihazlarda ölçülmeli.
- ELSA Speak'in özel modelleri veya algoritmaları kopyalanmadı ve kopyalandığı iddia edilmiyor.
