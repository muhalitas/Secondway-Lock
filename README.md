# Secondway Lock (Soft Mode)

`Secondway Lock`, kurumsal/enterprise admin moduna ihtiyaç duymadan çalışan bir Android self-control uygulamasıdır.

## Ne yapar?

- Yeni yüklenen kullanıcı uygulamalarını takip eder ve varsayılan olarak "blocked" durumuna alır.
- Kullanıcı bir uygulamayı `Allow` yaptığında, lock duration süresi dolunca otomatik olarak serbest bırakır.
- `Accessibility Guard` ile:
  - Bloklu uygulama açılışını yakalar.
  - Uygulamayı silme/devre dışı bırakma ekranlarını yakalayıp kısa bir bilgi ekranı gösterir ve ekrandan çıkar.

## Önemli sınırlar

Bu sürüm **soft mode** çalışır:

- Android sistem seviyesinde uninstall tamamen engellenemez.
- Factory reset sistem seviyesinde tamamen engellenemez.
- Koruma gücü, verilen izinlerin aktif kalmasına bağlıdır.

## Kurulum

1. APK'yı derleyin:
   - `./gradlew assembleDebug`
2. Cihaza kurun:
   - APK dosyasını cihazda açıp yükleyin (dosya yöneticisi ile) veya kendi dağıtım kanalınızı kullanın.
3. Uygulamayı açın ve **Settings** ekranından:
   - Accessibility Guard'ı etkinleştirin
   - Overlay iznini verin
   - Pil optimizasyon muafiyetini verin (önerilir)

## Temel bileşenler

- `MainActivity` - Koruma anahtarı, lock duration, uygulama listesi
- `PolicyHelper` - Soft-mode protection state + blocked package state
- `AccessibilityGuardService` - Bloklu app/tamper ekranı tespiti ve müdahale
- `GuardInterventionActivity` - Kısa bilgi ekranı
- `InstallMonitorService` - Arka planda yeni kurulum takibi
