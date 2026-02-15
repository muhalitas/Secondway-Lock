# Grok prompt: Lock duration değişince geri sayım davranışı

## Problem

Android uygulama: **Secondway Lock** (Kotlin, soft mode). Kullanıcı **protection** switch'ini OFF yaptığında, ayarlanan **lock duration** kadar bekleyip sonra uygulama içi koruma state'i kapanıyor (geri sayım var).

Kullanıcı geri sayım devam ederken **Lock duration** değerini değiştirebiliyor (ana ekranda "Lock duration" satırına tıklayıp picker’dan yeni süre seçiyor). İstenen davranış:

1. **Yeni süre daha DÜŞÜK seçilirse:** Mevcut geri sayım **kısalmamalı**. Yani bitiş zamanı aynen kalmalı, kalan süre kadar beklemeye devam etmeli (“mevcut süre kadar beklet”).
2. **Yeni süre daha YÜKSEK seçilirse:** Yeni lock duration **anında** geçerli olmalı; geri sayım `şimdi + yeni süre` olacak şekilde uzatılmalı.

Şu an bu davranış **çalışmıyor**: özellikle “daha düşük süre seçildiğinde mevcut süre kadar bekletme” kısmı gerçekleşmiyor (muhtemelen geri sayım kısalıyor veya bitiş zamanı kayboluyor).

---

## Teknik özet

- **SharedPreferences** (`lock_prefs`):  
  - `lock_duration_seconds` (Int): bir sonraki “protection off” için kullanılacak süre (saniye).  
  - `pending_protection_off_end` (Long): geri sayımın bittiği zaman (epoch ms). Bu değer `tickCountdowns()` ve `refreshUi()` tarafından okunuyor; süre dolunca `clearPendingProtectionOff` çağrılıp restriction kaldırılıyor.
- Süre değişimi tek yerden yapılıyor: kullanıcı duration picker’da OK’e basınca `onLockDurationChanged(newDurationSec)` çağrılıyor. Bu fonksiyon şu an `LockHelper.setLockDurationWithPendingRule(...)` kullanıyor; ama “daha düşük süre = bitiş zamanına dokunma” kuralı cihazda etkili olmuyor.

İstenen: **Daha düşük lock duration seçildiğinde `pending_protection_off_end` hiç değişmemeli** (mevcut geri sayım aynen devam etmeli). Daha yüksek seçildiğinde ise `pending_protection_off_end = now + newDurationSec * 1000L` yapılmalı.

---

## İlgili kodlar

### 1. LockHelper.kt (setLockDurationWithPendingRule)

```kotlin
fun setLockDurationWithPendingRule(
    context: Context,
    newDurationSec: Int,
    pendingEndTimeMillis: Long,
    nowMillis: Long
) {
    val edit = prefs(context).edit()
        .putInt(KEY_LOCK_DURATION_SECONDS, newDurationSec.coerceIn(0, MAX_DURATION))
    if (pendingEndTimeMillis > nowMillis) {
        val remainingSec = ((pendingEndTimeMillis - nowMillis) / 1000).toInt().coerceAtLeast(0)
        if (newDurationSec > remainingSec) {
            edit.putLong(KEY_PENDING_PROTECTION_OFF_END, nowMillis + newDurationSec * 1000L)
        }
        // else: putLong eklemiyoruz, mevcut pending aynen kalır
    }
    edit.apply()
}
```

Mantık: Sadece `newDurationSec > remainingSec` ise `KEY_PENDING_PROTECTION_OFF_END` yazılıyor; else’te bu key edit’e eklenmiyor. Buna rağmen cihazda “daha düşük süre” durumunda bekletme bozuluyor.

### 2. MainActivity – onLockDurationChanged

```kotlin
private fun onLockDurationChanged(newDurationSec: Int) {
    val now = System.currentTimeMillis()
    val pendingEnd = LockHelper.getPendingProtectionOffEndTime(this)
    LockHelper.setLockDurationWithPendingRule(this, newDurationSec, pendingEnd, now)
    updateLockDurationDisplay()
    refreshUi()
}
```

### 3. Geri sayımın okunduğu yerler

- `tickCountdowns()`: `LockHelper.getPendingProtectionOffEndTime(this)` ile okuyup saniye hesaplıyor; `now >= pendingProtectionEnd` olunca `clearPendingProtectionOff` + `refreshUi()`.
- `refreshUi()`: Aynı key ile kalan süreyi gösteriyor.
- Protection OFF yapılırken: `LockHelper.setPendingProtectionOff(activity, System.currentTimeMillis() + duration * 1000L)` ile bitiş zamanı set ediliyor.

---

## Senden istenen

1. **Neden “daha düşük süre” durumunda bekletme bozuluyor olabilir?**  
   (Örn: `SharedPreferences` edit’te sadece `putInt` yapıp `putLong` eklemediğimizde, bazı cihazlarda veya sürümlerde bu key’in silinip silinmediğini, apply/commit sırası, process ölmesi vs. düşün.)

2. **Çalışan bir çözüm öner:**  
   “Lock duration daha düşük seçildiğinde mevcut geri sayım (pending_protection_off_end) kesinlikle korunsun; daha yüksek seçildiğinde anında uzatılsın.” Bu davranışı garanti edecek şekilde:
   - Ya `LockHelper.setLockDurationWithPendingRule` (ve gerekirse `onLockDurationChanged`) mantığını değiştir,
   - Ya da farklı bir yaklaşım öner (örn. pending end’i ayrı bir yerde tutmak, `commit()` kullanmak, vb.).

Proje yolu: `SecondwayLock/` (LockHelper: `app/src/main/java/app/secondway/lock/LockHelper.kt`, MainActivity: `app/src/main/java/app/secondway/lock/MainActivity.kt`).
