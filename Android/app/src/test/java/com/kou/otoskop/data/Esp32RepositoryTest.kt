package com.kou.otoskop.data

import com.kou.otoskop.core.AppErrorKind
import com.kou.otoskop.core.Resource
import com.kou.otoskop.data.network.Esp32Endpoint
import com.kou.otoskop.data.repository.Esp32Repository
import com.kou.otoskop.data.repository.HttpEsp32Repository
import com.kou.otoskop.data.repository.MoveDirection
import com.kou.otoskop.data.repository.MoveStep
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class Esp32RepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: Esp32Repository

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val url = server.url("/")
        val endpoint = Esp32Endpoint(host = url.host, port = url.port)
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val api = Retrofit.Builder()
            .baseUrl(url)
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(com.kou.otoskop.data.network.Esp32Api::class.java)
        repo = HttpEsp32Repository(api, endpoint)
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `status parses JSON response`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"azimuth":120.5,"altitude":45.0,"targetAzimuth":121.0,
                 "targetAltitude":45.5,"servoAz":60.5,"servoAlt":45.0,
                 "gpsFix":true,"imuOk":true,"tracking":true,"targetLocked":false}
                """.trimIndent()
            )
        )

        val r = repo.status()
        assertTrue(r is Resource.Success)
        val s = (r as Resource.Success).value
        assertEquals(120.5, s.azimuth, 0.001)
        assertTrue(s.tracking)
        assertEquals(false, s.targetLocked)
    }

    @Test fun `sendTarget posts expected JSON body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val r = repo.sendTarget("Mars", 135.4, 42.8)
        assertTrue(r is Resource.Success)

        val req: RecordedRequest = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/target", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"name\":\"Mars\""))
        assertTrue(body.contains("\"azimuth\":135.4"))
        assertTrue(body.contains("\"altitude\":42.8"))
    }

    @Test fun `move serializes direction and step`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        repo.move(MoveDirection.RIGHT, MoveStep.LARGE)
        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"direction\":\"right\""))
        assertTrue(body.contains("\"step\":\"large\""))
    }

    @Test fun `network error produces ESP32_UNREACHABLE`() = runTest {
        server.shutdown() // force connection refused
        val r = repo.status()
        assertTrue(r is Resource.Failure)
        val err = (r as Resource.Failure).error
        assertEquals(AppErrorKind.ESP32_UNREACHABLE, err.kind)
        assertTrue(err.cause != null)
    }
}
