# Recovery Menüsü Engelleme – Bulgular (Grok özeti)

## 1. Standart API yok

DevicePolicyManager, UserManager veya diğer sistem API’lerinde, Device Owner’ın **donanım tuşlarıyla** (Volume+Power vb.) recovery moduna girişi engelleyecek **resmi public API yok**. Recovery’ye giriş bootloader seviyesinde; Android 9–14 (API 28–34) aralığında standart API ile kontrol edilmiyor.

## 2. Gizli kısıtlama yok

AOSP’ta `no_recovery`, `DISALLOW_RECOVERY` veya benzeri **gizli/@SystemApi kısıtlama** yok. RecoverySystemService, bootloader geçişi veya WindowManager bu tür bir kısıtlamayı kontrol etmiyor. Recovery donanım tuşlarıyla açılan düşük seviye bir boot bölümü; framework tarafında Device Owner için böyle bir engel tanımlı değil.

## 3. Samsung Knox ile recovery engeli

Samsung cihazlarda recovery’yi **gerçekten kapatmak** için **Knox SDK** kullanılır (Knox destekli cihazlar, genelde Android 9+). Üçüncü parti bir DPC, Knox Service Plugin veya doğrudan Knox SDK entegrasyonu ile şunu kullanabilir:

```java
// Knox EnterpriseDeviceManager örneği (edm) ile
RestrictionPolicy restrictionPolicy = edm.getRestrictionPolicy();
restrictionPolicy.allowFirmwareRecovery(false);  // Recovery modu ve recovery üzerinden factory reset kapatılır
```

- Device Owner yetkisi gerekir.
- Cihazın Knox desteklemesi gerekir (çoğu Samsung Android 9+).
- Standart Android API’si değil; DPC’ye **Knox SDK** entegre edilmelidir.

## 4. Root olmadan standart yöntem yok

Stok Android 9–14’te recovery menüsüne girişi **root kullanmadan** standart API ile kapatacak güvenilir bir yöntem yok; giriş bootloader tarafından yönetiliyor.

- **Factory Reset Protection (FRP):** Recovery’ye girişi engellemez; ancak recovery ile sıfırlama sonrası Google hesabı doğrulaması ister. Yetkisiz sıfırlamayı anlamsız kılar. Bu DPC’de `FactoryResetProtectionPolicy` (API 30+) zaten kullanılıyor.
- **OEM politikaları:** Samsung’da Knox SDK (yukarıdaki yöntem). Diğer OEM’ler için benzer özellik varsa kendi MDM/SDK’larına bakılmalı; cihazlar arası tek çözüm yok.

---

**Özet:** Stok Android’de recovery menüsüne tuşla giriş DPC ile kapatılamaz. Samsung’da recovery’yi kapatmak için Knox SDK’da `RestrictionPolicy.allowFirmwareRecovery(false)` kullanılmalı; bu projede isteğe bağlı Knox entegrasyonu için referans olarak bu dosya kullanılabilir.
