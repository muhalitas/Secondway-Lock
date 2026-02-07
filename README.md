# Secondway Lock

Kendi Android cihazınızda **Device Owner** yetkisiyle (DPC / Device Policy Controller) **yeni yüklenen uygulamaları varsayılan olarak kilitleyen** bir self-control uygulaması.

> **Geliştirme kuralı:** Her kod değişikliğinden sonra mutlaka **rebuild + reinstall** yapın:
> `./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Ne yapar?

- **Ayarlar üzerinden factory reset** engellenir (`DISALLOW_FACTORY_RESET`).
- **Yeni yüklenen uygulamalar** otomatik olarak **suspend** edilir (açılış engellenir).
- Kullanıcı bir uygulamayı “Allow” yapmak istediğinde:
  - **Protection ON** ise: **Lock duration** kadar bekler, süre dolunca uygulama otomatik serbest bırakılır.
  - **Protection OFF** ise: uygulama anında serbest bırakılır.

**Recovery menüsü (tuşla giriş):** Donanım tuşlarıyla (Volume+Power vb.) açılan recovery menüsü **standart Android API ile kapatılamaz**; bootloader seviyesinde. AOSP’ta gizli `no_recovery` kısıtlaması da yok. **Samsung** cihazlarda recovery’yi kapatmak için Knox SDK gerekir — ayrıntılar için `RECOVERY_BLOCK_FINDINGS.md` ve aşağıdaki “Samsung Knox” bölümüne bakın.

## Gereksinimler

- Android 7.0+ (API 24+)
- Cihazda **hiç hesap eklenmemiş** olmalı (veya fabrika sıfırı sonrası kurulum adımları tamamlanmamalı) — Device Owner ataması için

## ADB ile kurulum ve Device Owner atama

1. **APK’yı derleyin**
   - **Android Studio:** Projeyi açın (File → Open → proje klasörü), Gradle sync bitince Build → Build Bundle(s) / APK(s) → Build APK(s).
   - **Komut satırı:** Gradle wrapper varsa `./gradlew assembleDebug`, yoksa önce projeyi Android Studio ile açıp sync yapın; ardından `./gradlew assembleDebug`.
   - APK: `app/build/outputs/apk/debug/app-debug.apk`

2. **Cihazı bağlayın**, USB hata ayıklama açık olsun.

3. **APK’yı yükleyin:**
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

4. **Device Owner atayın** (cihazda **hesap olmamalı**; gerekirse fabrika sıfırı yapıp kurulumda hesap eklemeyin):
   ```bash
   adb shell dpm set-device-owner com.recoverylock.dpc/.DeviceAdminReceiver
   ```
   Başarılı olursa: `Success: Device owner set to package com.recoverylock.dpc`.

5. Uygulamayı açıp **“Kısıtlamaları Uygula”** butonuna basın.

## Device Owner ataması başarısız olursa

- **“Not allowed to set the device owner…”**  
  Cihazda zaten bir hesap var. Ya tüm hesapları kaldırın ya da fabrika sıfırı yapıp kurulumda hesap eklemeyin; sonra tekrar `dpm set-device-owner` deneyin.

- **“Admin is already set”**  
  Başka bir cihaz yöneticisi var. Ayarlar → Güvenlik → Cihaz yöneticileri’nden kaldırıp tekrar deneyin (veya fabrika sıfırı).

- **“Package has no receivers”**  
  Uygulama doğru yüklenmemiş; `adb install -r` ile tekrar yükleyin.

## Device Owner’ı kaldırma

Cihazı tekrar tam kontrol etmek isterseniz:

```bash
adb shell dpm remove-active-admin com.recoverylock.dpc/.DeviceAdminReceiver
```

Ardından uygulamayı kaldırabilirsiniz.

## Samsung Knox ile recovery engeli

Samsung cihazlarda recovery menüsüne girişi **gerçekten** kapatmak için [Knox SDK](https://developer.samsung.com/knox) entegre edilmeli. Device Owner + Knox destekli cihazda:

```java
RestrictionPolicy restrictionPolicy = edm.getRestrictionPolicy();
restrictionPolicy.allowFirmwareRecovery(false);
```

Detaylar ve Grok bulguları: **`RECOVERY_BLOCK_FINDINGS.md`**.

## Proje yapısı

- `DeviceAdminReceiver` — Device Owner ataması için receiver
- `PolicyHelper` — `DevicePolicyManager` ile kısıtlamalar + paket suspend/unsuspend
- `NewAppLockStore` — yeni uygulama kilit listesi + pending unlock state
- `NewAppUnlockReceiver` — süre dolunca otomatik unsuspend (AlarmManager)
- `MainActivity` — Protection + lock duration + “Newly installed apps” listesi

## Lisans

Kendi cihazınızda kullanım için; eğitim ve kişisel kullanım amaçlıdır.
