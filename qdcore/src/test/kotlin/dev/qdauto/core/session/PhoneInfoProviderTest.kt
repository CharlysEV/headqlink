package dev.qdauto.core.session

import dev.qdauto.core.TestSupport.waitUntil
import dev.qdauto.core.json.JsonObject
import dev.qdauto.core.json.JsonParser
import dev.qdauto.core.wire.CarInfo
import dev.qdauto.core.wire.CarMessages
import dev.qdauto.core.wire.Cmd
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** hql (C3): `PHONE_INFO` según `CAR_INFO` ([SessionConfig.phoneInfoOverridesFor]). */
class PhoneInfoProviderTest {
    private val closeables = ArrayList<AutoCloseable>()

    @AfterTest
    fun cleanup() = closeables.reversed().forEach { runCatching { it.close() } }

    /** Como el fork: los ocho campos Phone… y Mirror… al tamaño del vídeo (aquí, la mitad del coche; 800×480 si falta). */
    private fun forkLike(car: CarInfo?): PhoneInfoOverrides {
        val w = car?.carWidth?.takeIf { it > 0 } ?: 1600
        val h = car?.carHeight?.takeIf { it > 0 } ?: 960
        return PhoneInfoOverrides(w / 2, h / 2, w / 2, h / 2, w / 2, h / 2, w / 2, h / 2)
    }

    private fun para(json: String): JsonObject = JsonParser.parseObject(json).obj("PARA")!!

    @Test
    fun phoneInfoAfterCarInfoAndOnResendUseTheProvider() {
        val server = MirrorServer(0).also { closeables += it }
        val car = FakeCar(server.port).also { closeables += it }
        val asked: MutableList<CarInfo?> = Collections.synchronizedList(ArrayList())
        val s = PhoneSession(
            server.accept(5_000),
            SessionConfig(
                heartbeatInitialDelayMs = 60_000,
                phoneInfoOverridesFor = { c ->
                    asked += c
                    forkLike(c)
                },
            ),
        ).also { closeables += it }.start()
        car.next()
        // Antes de CAR_INFO: el proveedor recibe null.
        s.resendPhoneInfo()
        val before = para(car.nextJson())
        assertEquals(800, before.int("PhoneWidth"))
        assertEquals(480, before.int("MirrorHeightInApp"))
        assertNull(asked.single())
        car.send(
            CarMessages.carInfo(
                JsonObject.of(
                    "Version" to "4.9", "CarType" to "2D4", "Platform" to 0, "PlatformVersion" to "", "CarWidth" to 1920,
                    "CarHeight" to 882, "CarFactory" to "018", "HUFactory" to "119", "MirrorTypeReq" to 2,
                ),
            ),
        )
        val p = para(car.nextJson())
        for (k in listOf("PhoneWidth", "MirrorWidth", "PhoneWidthInApp", "MirrorWidthInApp")) assertEquals(960, p.int(k), k)
        for (k in listOf("PhoneHeight", "MirrorHeight", "PhoneHeightInApp", "MirrorHeightInApp")) assertEquals(441, p.int(k), k)
        assertEquals(2, p.int("MirrorTypeSupport"))
        assertEquals(1920, asked.last()?.carWidth)
        assertEquals(Cmd.UPDATE_NOTIFY, JsonParser.parseObject(car.nextJson()).string("CMD"))
        s.resendPhoneInfo()
        assertEquals(960, para(car.nextJson()).int("MirrorWidth"))
        assertEquals(3, asked.size)
        assertEquals(960, s.lastPhoneInfo?.mirrorWidth)
    }

    @Test
    fun withoutProviderTheFixedOverridesStillApply() {
        val server = MirrorServer(0).also { closeables += it }
        val car = FakeCar(server.port).also { closeables += it }
        PhoneSession(
            server.accept(5_000),
            SessionConfig(heartbeatInitialDelayMs = 60_000, phoneInfoOverrides = PhoneInfoOverrides(phoneWidth = 1234)),
        ).also { closeables += it }.start()
        car.next()
        car.send(CarMessages.carInfo(JsonObject.of("CarWidth" to 1920)))
        val p = para(car.nextJson())
        assertEquals(1234, p.int("PhoneWidth"))
    }

    @Test
    fun aThrowingProviderFallsBackToTheFixedOverrides() {
        val server = MirrorServer(0).also { closeables += it }
        val car = FakeCar(server.port).also { closeables += it }
        val s = PhoneSession(
            server.accept(5_000),
            SessionConfig(
                heartbeatInitialDelayMs = 60_000,
                phoneInfoOverrides = PhoneInfoOverrides(mirrorWidth = 777),
                phoneInfoOverridesFor = { throw IllegalStateException("prueba") },
            ),
        ).also { closeables += it }.start()
        car.next()
        s.resendPhoneInfo()
        assertEquals(777, para(car.nextJson()).int("MirrorWidth"))
        assertEquals(true, waitUntil(1_000) { !s.isClosed })
    }
}
