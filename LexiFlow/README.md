# LexiFlow — İngilizce kelimeden akıcılığa (Android)

> **I recognize this word → I can recall it → I can use it naturally.**

LexiFlow, gördüğün kelimeyi tanıyıp konuşurken hatırlayamama sorununu çözmeyi hedefleyen, çevrim dışı çalışan bir Android uygulamasıdır. İçinde şunlar var:
- Anki tarzı desteler ve **FSRS-6** zamanlayıcı
- Ayrı bir aktif kullanım planlayıcısı
- Kilit ekranı kelime soruları
- Hikâyeler, konuşma koçu, telaffuz laboratuvarı
- Kelime yarışması ve ilerleme paneli

## Hızlı kurulum (telefon)
1. `dist/LexiFlow-1.2.0.apk` dosyasını telefona indir.
2. Dosyaya dokun → "Bilinmeyen kaynaklardan yüklemeye izin ver" → Yükle.
3. Uygulamayı aç; seviyeni ve günlük hedefini seç, kilit ekranı sorularını açıp bildirim iznini ver.

Gereken Android sürümü: **8.0 (API 26)** veya üstü. Hedef Android 15 (API 35).

Bu APK yerel *debug* anahtarıyla imzalanmış bir **test sürümüdür**; Google Play'e yüklenemez. Play için kendi anahtarını `keystore.properties` ile tanımla (aşağıda).

## Geliştirici: derleme
```bash
cd LexiFlow
./gradlew :core:test :app:testDebugUnitTest :backend:test   # tüm testler
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # app/build/outputs/apk/release/app-release.apk
```
Gerekenler: JDK 17+ ve Android SDK (platform 35, build-tools 35). Yollar `local.properties` içinde `sdk.dir=...` olarak yazılır.

**Release imzası:** Kök dizine `keystore.properties` ekle (git'e girmez):
```
storeFile=/yol/lexiflow-release.jks
storePassword=...
keyAlias=lexiflow
keyPassword=...
```

## İsteğe bağlı sunucu (AI + online yarışma)
Uygulama sunucusuz tam çalışır. AI hikâyeleri, AI konuşma, kelime zenginleştirme ve online yarışma için `backend/README.md`'deki adımlarla Ktor sunucusunu çalıştır. Ardından uygulamada **Ayarlar → Sunucu → adres → Bağlan**.
- API anahtarı (`ANTHROPIC_API_KEY`) yalnızca sunucunun `.env` dosyasında tutulur, uygulamaya asla girmez.

## Dokümanlar
| Konu | Dosya |
|---|---|
| Mimari | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| FSRS | [docs/FSRS.md](docs/FSRS.md) |
| Aktif hatırlama | [docs/ACTIVE_RECALL.md](docs/ACTIVE_RECALL.md) |
| Kilit ekranı | [docs/LOCK_SCREEN.md](docs/LOCK_SCREEN.md) |
| Telaffuz | [docs/PRONUNCIATION.md](docs/PRONUNCIATION.md) |
| Veritabanı | [docs/DATABASE.md](docs/DATABASE.md) |
| Modlar | [docs/MODES.md](docs/MODES.md) |
| Repolar | [docs/REPOSITORIES.md](docs/REPOSITORIES.md) |
| Lisanslar | [docs/CONTENT_SOURCES_AND_LICENSING.md](docs/CONTENT_SOURCES_AND_LICENSING.md) |
| Gereksinimler | [docs/REQUIREMENTS_CHECKLIST.md](docs/REQUIREMENTS_CHECKLIST.md) |
| Testler | [docs/TEST_REPORT.md](docs/TEST_REPORT.md) |
| Kod incelemesi | [docs/CODERABBIT_REPORT.md](docs/CODERABBIT_REPORT.md) |
| Ek özellikler | [docs/ADDITIONAL_FEATURES.md](docs/ADDITIONAL_FEATURES.md) |
| Son rapor | [docs/FINAL_IMPLEMENTATION_REPORT.md](docs/FINAL_IMPLEMENTATION_REPORT.md) |
