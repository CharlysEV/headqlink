package dev.qdauto.core.session

import dev.qdauto.core.json.JsonObject
import dev.qdauto.core.wire.CarInfo
import dev.qdauto.core.wire.PhoneInfo

/** Construye `PHONE_INFO` como QDLink (LC/a.java:914-959, spec §6.4) a partir de `CAR_INFO` y del teléfono. */
object PhoneInfoFactory {
    fun build(car: CarInfo?, phone: PhoneIdentity, overrides: PhoneInfoOverrides = PhoneInfoOverrides()): PhoneInfo {
        val carW = car?.carWidth ?: 0
        val carH = car?.carHeight ?: 0
        val g = MirrorGeometry.forCarInfo(phone.screenLongSide, phone.screenShortSide, carW, carH)
        return PhoneInfo(
            phoneWidth = overrides.phoneWidth ?: maxOf(phone.screenLongSide, phone.screenShortSide),
            phoneHeight = overrides.phoneHeight ?: minOf(phone.screenLongSide, phone.screenShortSide),
            mirrorWidth = overrides.mirrorWidth ?: g.mirrorWidth,
            mirrorHeight = overrides.mirrorHeight ?: g.mirrorHeight,
            // Eco crudo de CarWidth/CarHeight, aunque sean 0 (LC/a.java:924-927).
            phoneWidthInApp = overrides.phoneWidthInApp ?: carW,
            phoneHeightInApp = overrides.phoneHeightInApp ?: carH,
            mirrorWidthInApp = overrides.mirrorWidthInApp ?: carW,
            mirrorHeightInApp = overrides.mirrorHeightInApp ?: carH,
            phoneFeature = JsonObject.of("PassistMobileNum" to phone.passistMobileNum),
            phoneUuid = phone.phoneUuid,
            phoneName = phone.phoneName,
            version = phone.appVersion,
            phoneBrand = phone.brand,
            phoneModel = phone.model,
            platform = overrides.platform ?: 0,
            platformVersion = overrides.platformVersion ?: phone.sdkInt.toString(),
            mirrorTypeSupport = overrides.mirrorTypeSupport ?: (car?.mirrorTypeReq ?: 0),
        )
    }
}
