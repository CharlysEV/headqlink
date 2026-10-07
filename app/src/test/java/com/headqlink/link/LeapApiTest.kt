package com.headqlink.link

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.Base64
import java.util.Random

/**
 * Cliente de la nube con una capa HTTP de mentira: el login con el certificado del usuario y su firma SHA-256, lo demás
 * con el PKCS#12 de cuenta (contraseña derivada) y cabeceras HMAC, el refresco del token y sus fallos, los errores de la
 * API y que solo se piden las rutas de lectura.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class LeapApiTest {
    private fun identity(kp: java.security.KeyPair, cn: String) = LeapTls.Identity(kp.private, arrayOf(TestCerts.selfSigned(kp, cn)))

    private class Call(val identity: LeapTls.Identity, val path: String, val headers: Map<String, String>, val body: String)

    private class Fake(var handler: (Call) -> LeapApi.Response) : LeapApi.Transport {
        val calls = ArrayList<Call>()
        override fun post(identity: LeapTls.Identity, path: String, headers: Map<String, String>, body: String): LeapApi.Response {
            val c = Call(identity, path, HashMap(headers), body)
            calls.add(c)
            return handler(c)
        }
    }

    private val now = 1791297600000L
    private val clientId = identity(TestCerts.ec, "cliente")
    private val accountCert = TestCerts.selfSigned(TestCerts.rsa, "cuenta")
    private val token1 = jwt("a,b,dev-xyz,d")

    private fun jwt(userName: String) = "h." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""{"user_name":"$userName"}""".toByteArray()) + ".s"

    private fun ok(data: String) = LeapApi.Response(200, """{"code":0,"message":"ok","data":$data}""")

    private fun loginData(): String {
        val pw = LeapCrypto.accountP12Password("1234567", "u-ABCDEFGH-0001").toCharArray()
        val p12 = Base64.getEncoder().encodeToString(TestCerts.pkcs12(TestCerts.rsa.private, arrayOf(accountCert), pw))
        return """{"id":1234567,"token":"$token1","refreshToken":"rt-1","signIkm":"ikm-demo","signSalt":"salt-demo",
            |"signInfo":"info-demo","uid":"u-ABCDEFGH-0001","base64Cert":"$p12"}""".trimMargin()
    }

    private fun api(fake: Fake) = LeapApi(fake, clientId, { now }, Random(1), "0123456789abcdef0123456789abcdef")

    /** Comprueba la firma HMAC de una petición autenticada rehaciéndola con los campos de sus cabeceras. */
    private fun assertSigned(c: Call, extra: Map<String, String>) {
        val f = linkedMapOf(
            "acceptLanguage" to c.headers["acceptLanguage"]!!, "channel" to c.headers["channel"]!!,
            "deviceId" to c.headers["deviceId"]!!, "deviceType" to c.headers["deviceType"]!!, "nonce" to c.headers["nonce"]!!,
            "source" to c.headers["source"]!!, "timestamp" to c.headers["timestamp"]!!, "version" to c.headers["version"]!!)
        f.putAll(extra)
        assertEquals(LeapCrypto.sign(LeapCrypto.signKey("ikm-demo", "salt-demo", "info-demo"), f), c.headers["sign"])
    }

    private fun loggedIn(handler: (Call) -> LeapApi.Response): Pair<LeapApi, Fake> {
        val fake = Fake { ok(loginData()) }
        val a = api(fake)
        a.login("demo@example.com", "p@ss w0rd")
        fake.calls.clear()
        fake.handler = handler
        return a to fake
    }

    @Test
    fun loginWithTheClientCertificateAndTheAppSignature() {
        val fake = Fake { ok(loginData()) }
        val a = api(fake)
        val s = a.login("demo@example.com", "p@ss w0rd")
        val c = fake.calls.single()
        assertEquals(LeapApi.PATH_LOGIN, c.path)
        assertSame("el login va con el certificado del usuario", clientId, c.identity)
        assertEquals("0123456789abcdef0123456789abcdef", c.headers["deviceId"])
        assertEquals(now.toString(), c.headers["timestamp"])
        assertEquals(LeapCrypto.loginSign("0123456789abcdef0123456789abcdef", "demo@example.com", c.headers["nonce"]!!, "p@ss w0rd",
            now.toString()), c.headers["sign"])
        assertEquals("isRecoverAcct=0&password=p%40ss%20w0rd&policyId=20260204&loginMethod=1&email=demo%40example.com", c.body)
        assertEquals("application/x-www-form-urlencoded", c.headers["Content-Type"])
        assertEquals("1", c.headers["X-P12_ENC_ALG"])
        assertEquals("en-GB", c.headers["acceptLanguage"])
        assertEquals("1.12.3", c.headers["version"])
        assertEquals("leapmotor", c.headers["source"])
        // La sesión: el id, el token, el deviceId del token y el PKCS#12 de cuenta abierto con su contraseña derivada.
        assertEquals("1234567", s.userId)
        assertEquals("1234567", s.accountId)
        assertEquals("dev-xyz", s.deviceId)
        assertEquals("rt-1", s.refreshToken)
        assertTrue(a.loggedIn())
        // La sesión se guarda y se recupera tal cual.
        val back = LeapApi.Session.fromJson(JSONObject(s.toJson().toString()))
        assertEquals(s.token, back.token)
        assertEquals(s.base64Cert, back.base64Cert)
        assertTrue(back.usable())
    }

    @Test
    fun vehiclesAndStatusWithTheAccountCertificateAndHmac() {
        val (a, fake) = loggedIn { c ->
            when {
                c.path == LeapApi.PATH_VEHICLES -> ok("""{"bindcars":[{"vin":"VIN000000000000A1","carType":"C10","nickName":null}],
                    "sharedcars":[{"vin":"VIN000000000000B2","carType":"B10","nickName":"Familia"}]}""")
                c.path.startsWith(LeapApi.PATH_STATUS) -> ok("""{"signal":{"1204":70,"1318":12000}}""")
                else -> LeapApi.Response(404, "")
            }
        }
        val cars = a.vehicles()
        assertEquals(2, cars.size)
        assertEquals("C10", cars[0].carType)
        assertEquals("", cars[0].nickName)
        assertFalse(cars[0].shared)
        assertTrue(cars[1].shared)
        assertEquals("c10", cars[1].statusPath())
        val list = fake.calls.single()
        assertNotSame("lo demás va con el certificado de cuenta", clientId, list.identity)
        assertEquals(accountCert, list.identity.leaf())
        assertEquals("1234567", list.headers["userId"])
        assertEquals(token1, list.headers["token"])
        assertEquals("dev-xyz", list.headers["deviceId"])
        assertEquals("", list.body)
        assertSigned(list, emptyMap())

        val data = a.status("VIN000000000000A1", "C10")
        val st = fake.calls.last()
        assertEquals("/carownerservice/oversea/vehicle/v1/status/get/c10", st.path)
        assertEquals("vin=VIN000000000000A1", st.body)
        assertSigned(st, mapOf("vin" to "VIN000000000000A1"))
        assertEquals(70.0, LeapStatus.parse(data).soc, 0.0)
    }

    @Test
    fun anExpiredTokenIsRefreshedOnceAndTheCallRepeated() {
        var statusCalls = 0
        val (a, fake) = loggedIn { c ->
            when (c.path) {
                LeapApi.PATH_REFRESH -> ok("""{"token":"tok-2","refreshToken":"rt-2"}""")
                else -> if (statusCalls++ == 0) LeapApi.Response(200, """{"code":401,"message":"Token expired"}""")
                else ok("""{"signal":{"1204":55}}""")
            }
        }
        val v0 = a.sessionVersion()
        val data = a.status("VIN000000000000A1", "C10")
        assertEquals(55.0, LeapStatus.parse(data).soc, 0.0)
        assertEquals(listOf("status", "refresh", "status"), fake.calls.map { if (it.path == LeapApi.PATH_REFRESH) "refresh" else "status" })
        val refresh = fake.calls[1]
        assertEquals("refreshToken=rt-1", refresh.body)
        assertEquals(token1, refresh.headers["token"])
        assertSigned(refresh, mapOf("refreshToken" to "rt-1"))
        assertEquals("tok-2", fake.calls[2].headers["token"])
        assertEquals("rt-2", a.session().refreshToken)
        assertTrue("la sesión cambió: hay que guardarla", a.sessionVersion() > v0)
    }

    @Test
    fun aRejectedRefreshMeansTheSessionExpired() {
        val (a, fake) = loggedIn { c ->
            if (c.path == LeapApi.PATH_REFRESH) LeapApi.Response(200, """{"code":1001,"message":"refresh token invalid"}""")
            else LeapApi.Response(401, """{"code":401,"message":"unauthorized"}""")
        }
        try {
            a.status("VIN000000000000A1", "C10")
            fail("debía caducar")
        } catch (e: LeapApi.SessionExpiredException) {
            // bien: hay que volver a entrar
        }
        assertEquals("un solo refresco, sin bucles", 1, fake.calls.count { it.path == LeapApi.PATH_REFRESH })
        assertEquals(1, fake.calls.count { it.path != LeapApi.PATH_REFRESH })
    }

    @Test
    fun aRefreshWithoutANewRefreshTokenKeepsTheOldOne() {
        var first = true
        val (a, _) = loggedIn { c ->
            if (c.path == LeapApi.PATH_REFRESH) ok("""{"token":"tok-3"}""")
            else if (first) { first = false; LeapApi.Response(200, """{"code":2,"message":"invalid token"}""") }
            else ok("{}")
        }
        a.status("VIN000000000000A1", "C10")
        assertEquals("tok-3", a.session().token)
        assertEquals("rt-1", a.session().refreshToken)
    }

    @Test
    fun otherErrorsDoNotRefresh() {
        val (a, fake) = loggedIn { LeapApi.Response(200, """{"code":500,"message":"vehicle offline"}""") }
        try {
            a.status("VIN000000000000A1", "C10")
            fail()
        } catch (e: LeapApi.ApiException) {
            assertEquals(500, e.apiCode)
            assertEquals("vehicle offline", e.serverMessage)
            assertFalse(e.tokenProblem())
        }
        assertEquals(0, fake.calls.count { it.path == LeapApi.PATH_REFRESH })
    }

    @Test
    fun bodyParsing() {
        try {
            LeapApi.parse(LeapApi.Response(502, "<html>bad gateway</html>"), "vehicle status")
            fail()
        } catch (e: LeapApi.ApiException) {
            assertEquals(-1, e.apiCode)
            assertEquals(502, e.httpCode)
            assertFalse("el cuerpo no se copia al mensaje", e.message!!.contains("html"))
        }
        try {
            LeapApi.parse(LeapApi.Response(500, """{"code":0}"""), "x")
            fail("HTTP 500 aunque diga code 0")
        } catch (e: LeapApi.ApiException) {
            assertEquals(500, e.httpCode)
        }
        try {
            LeapApi.parse(LeapApi.Response(200, """{"code":"0"}"""), "x")
            fail("code tiene que ser el número 0, como en el Dart")
        } catch (e: LeapApi.ApiException) {
            assertEquals(-1, e.apiCode)
        }
        assertTrue(LeapApi.ApiException(401, -1, "").tokenProblem())
        assertEquals("ok", LeapApi.parse(LeapApi.Response(200, """{"code":0,"message":"ok"}"""), "x").getString("message"))
    }

    @Test
    fun onlyReadOnlyPaths() {
        assertTrue(LeapApi.allowed(LeapApi.PATH_LOGIN))
        assertTrue(LeapApi.allowed(LeapApi.PATH_REFRESH))
        assertTrue(LeapApi.allowed(LeapApi.PATH_VEHICLES))
        assertTrue(LeapApi.allowed(LeapApi.PATH_STATUS + "c10"))
        assertTrue(LeapApi.allowed(LeapApi.PATH_TRIPS))
        assertTrue(LeapApi.allowed(LeapApi.PATH_WEEKLY_EC))
        for (p in listOf(
            "/carownerservice/oversea/vehicle/v1/app/remote/ctl",
            "/carownerservice/oversea/vehicle/v1/app/remote/ctl/result/query",
            "/carownerservice/oversea/vehicle/v1/app/remote/ctl/getAppointment",
            "/carownerservice/oversea/vehicle/v1/operPwd/verify",
            "/carownerservice/oversea/vehicle/v1/cert/sync",
            "/carownerservice/oversea/message/v1/list",
            "/carownerservice/charge/daily/detail/page",
            LeapApi.PATH_STATUS + "c10/../../app/remote/ctl",
            LeapApi.PATH_STATUS,
            null,
        )) assertFalse(p ?: "null", LeapApi.allowed(p))
        // Ningún método del cliente manda órdenes (ni existen: solo login, refresco, lista y estado).
        val names = LeapApi::class.java.declaredMethods.map { it.name.lowercase() }
        for (bad in listOf("lock", "unlock", "remote", "ctl", "climate", "window", "trunk", "sentry", "pin", "operate", "command"))
            assertFalse(bad, names.any { it.contains(bad) })
    }

    @Test
    fun tripHistoryIsJsonWithThePagingSignedAsText() {
        val (a, fake) = loggedIn {
            LeapApi.Response(200, """{"code":"0","data":{"pageNum":1,"pageSize":20,"totalPage":1,"total":1,"list":[]}}""")
        }
        val d = a.tripsPage("VIN000000000000A1", 1790000000L, 1791297600L, 1)
        assertEquals(1, d.getInt("totalPage"))
        val c = fake.calls.single()
        assertEquals(LeapApi.PATH_TRIPS, c.path)
        assertEquals("application/json", c.headers["Content-Type"])
        assertEquals("VIN000000000000A1", c.headers["carvin"])
        val body = JSONObject(c.body)
        assertEquals("VIN000000000000A1", body.getString("vin"))
        assertEquals("1790000000", body.getString("startTime"))
        assertEquals("1791297600", body.getString("endTime"))
        // Se envían como número…
        assertTrue(body.get("pageNum") is Int)
        assertEquals(20, body.getInt("pageSize"))
        // …y se firman como texto, con el VIN.
        assertSigned(c, mapOf("vin" to "VIN000000000000A1", "startTime" to "1790000000", "endTime" to "1791297600",
            "pageNum" to "1", "pageSize" to "20"))
    }

    @Test
    fun weeklyConsumptionIsAFormWithTheCarvinSigned() {
        val (a, fake) = loggedIn { ok("""{"rankResult":{"hundredKmEC":"16.8"},"weeklyEC":[]}""") }
        val d = a.weeklyConsumption("VIN000000000000A1")
        assertEquals("16.8", d.getJSONObject("rankResult").getString("hundredKmEC"))
        val c = fake.calls.single()
        assertEquals(LeapApi.PATH_WEEKLY_EC, c.path)
        assertEquals("carvin=VIN000000000000A1", c.body)
        assertSigned(c, mapOf("carvin" to "VIN000000000000A1"))
    }

    @Test
    fun theTransportNeverSeesAPathOutsideTheList() {
        val (a, fake) = loggedIn { ok("{}") }
        a.vehicles()
        a.status("VIN000000000000A1", "T03")
        assertEquals(LeapApi.PATH_STATUS + "t03", fake.calls.last().path)
        for (c in fake.calls) assertTrue(c.path, LeapApi.allowed(c.path))
    }
}
