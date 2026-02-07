# Bu Uygulamada Kullanılan Device Owner Yetkileri

Device Owner olmadan bu kısıtlamaların hiçbiri uygulanamaz; normal uygulama veya sadece Device Admin yetkisi yeterli değildir.

---

## 1. Kullanıcı kısıtlamaları (User restrictions)

**API:** `DevicePolicyManager.addUserRestriction(admin, restriction)` / `clearUserRestriction(...)`

Sadece **Device Owner** (ve bazı durumlarda Profile Owner) bu kısıtlamaları ekleyip kaldırabilir. Bu uygulamada kullanılanlar:

| Kısıtlama | Ne yapar | API / not |
|-----------|----------|-----------|
| **DISALLOW_FACTORY_RESET** | Ayarlar > Yedekleme ve sıfırlama üzerinden factory reset’i engeller. | `UserManager.DISALLOW_FACTORY_RESET` |
| **DISALLOW_SAFE_BOOT** | Güvenli moda geçişi engeller; sistem `Settings.Global.SAFE_BOOT_DISALLOWED` değerini 1 yapar. | `UserManager.DISALLOW_SAFE_BOOT` |
| **DISALLOW_ADD_USER** | Yeni kullanıcı (profil) eklenmesini engeller. | Android 9+ (API 28), `UserManager.DISALLOW_ADD_USER` |
| **no_recovery** | Deneme amaçlı eklenir; AOSP’ta tanımlı değil, stok Android’de etkisi yok. | OEM’ler tanıyabilir |

**Avantaj:** Bu kısıtlamalar cihaz genelinde (ana kullanıcı / tüm kullanıcılar) uygulanır; Device Owner tek bir uygulama olarak tüm cihazı bu politikaya bağlayabilir.

---

## 2. Factory Reset Protection politikası (API 30+)

**API:** `DevicePolicyManager.setFactoryResetProtectionPolicy(admin, policy)`

Sadece **Device Owner** (ve organizasyon cihazında Profile Owner) bu politikayı ayarlayabilir.

- **Ne yapar:** Sıfırlama sonrası cihazın tekrar kullanılabilmesi için hesap doğrulaması (FRP) zorunluluğu getirilebilir.
- **Bu uygulamada:** `FactoryResetProtectionPolicy.Builder().setFactoryResetProtectionEnabled(true)` ile FRP etkinleştiriliyor; sıfırlama sonrası yetkisiz kullanım zorlaşıyor (recovery’ye girişi engellemez, sadece sıfırlama sonrası kilidi güçlendirir).

---

## 3. Device Owner kontrolü

**API:** `DevicePolicyManager.isDeviceOwnerApp(packageName)`

- Uygulama kendisinin Device Owner olup olmadığını kontrol ediyor.
- Sadece Device Owner ise “Kısıtlamaları Uygula / Kaldır” işlemleri yapılıyor; değilse butonlar devre dışı ve kullanıcıya uyarı gösteriliyor.

---

## Özet tablo

| Yetki / özellik | Bu uygulamada kullanım |
|------------------|------------------------|
| User restriction: factory reset (Ayarlar) | ✅ Kullanılıyor |
| User restriction: güvenli mod | ✅ Kullanılıyor |
| User restriction: yeni kullanıcı ekleme (API 28+) | ✅ Kullanılıyor |
| User restriction: no_recovery | ⚠️ Deneniyor (AOSP’ta etkisiz) |
| Factory Reset Protection policy (API 30+) | ✅ Kullanılıyor |
| Device Owner kontrolü (isDeviceOwnerApp) | ✅ Kullanılıyor |

Bu yetkilerin tamamı **Device Owner** olmadan kullanılamaz; sadece Device Admin yetkisi bu kısıtlamaları uygulamak için yeterli değildir.
