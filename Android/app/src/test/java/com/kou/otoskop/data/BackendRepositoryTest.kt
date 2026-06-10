package com.kou.otoskop.data

import com.kou.otoskop.core.AppErrorKind
import com.kou.otoskop.core.Resource
import com.kou.otoskop.data.model.SkyArea
import com.kou.otoskop.data.network.NetworkFactory
import com.kou.otoskop.data.repository.BackendRepository
import com.kou.otoskop.data.repository.HttpBackendRepository
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class BackendRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: BackendRepository

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val api = NetworkFactory.createBackend(
            baseUrl = server.url("/").toString(),
            overrideClient = OkHttpClient.Builder().build(),
        )
        repo = HttpBackendRepository(api)
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `observableObjects sends correct query params`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[{"name":"Mars","type":"planet","azimuth":135.4,
                   "altitude":42.8,"magnitude":-1.2,"visible":true}]""".trimIndent()
            )
        )

        val r = repo.observableObjects(
            latitude = 41.0,
            longitude = 29.0,
            datetime = Instant.parse("2026-05-14T20:00:00Z"),
            area = SkyArea(
                centerAzimuth = 135.0,
                centerAltitude = 40.0,
                halfWidthDeg = 10.0,
            ),
        )

        assertTrue(r is Resource.Success)
        val list = (r as Resource.Success).value
        assertEquals(1, list.size)
        assertEquals("Mars", list[0].name)

        val req = server.takeRequest()
        val path = req.requestUrl!!
        assertEquals("41.0", path.queryParameter("latitude"))
        assertEquals("125.0", path.queryParameter("azimuthMin"))
        assertEquals("145.0", path.queryParameter("azimuthMax"))
    }

    @Test fun `verifyImage with correction returns Success`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"verified":false,"targetName":"Mars",
                   "azimuthCorrection":-0.8,"altitudeCorrection":0.5,
                   "message":"Move slightly left and up"}""".trimIndent()
            )
        )

        val r = repo.verifyImage(
            targetName = "Mars",
            latitude = 41.0,
            longitude = 29.0,
            azimuth = 135.0,
            altitude = 42.0,
            imageBytes = byteArrayOf(1, 2, 3),
        )

        assertTrue(r is Resource.Success)
        val v = (r as Resource.Success).value
        assertEquals(false, v.verified)
        assertTrue(v.needsCorrection)
        assertEquals(-0.8, v.azimuthCorrection, 0.001)
    }

    @Test fun `verifyImage without correction and not verified maps to TARGET_NOT_VERIFIED`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"verified":false,"targetName":"Mars",
                   "azimuthCorrection":0,"altitudeCorrection":0,
                   "message":"no match"}""".trimIndent()
            )
        )

        val r = repo.verifyImage(
            "Mars", 0.0, 0.0, 0.0, 0.0, byteArrayOf(),
        )

        assertTrue(r is Resource.Failure)
        assertEquals(AppErrorKind.TARGET_NOT_VERIFIED, (r as Resource.Failure).error.kind)
    }
}
