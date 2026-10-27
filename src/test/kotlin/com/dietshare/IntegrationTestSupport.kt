package com.dietshare

import com.dietshare.storage.InMemoryStorageService
import com.dietshare.storage.StorageService
import com.dietshare.user.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.imageio.ImageIO

class KPostgres : PostgreSQLContainer<KPostgres>(DockerImageName.parse("postgres:16"))

class KRedis : GenericContainer<KRedis>(DockerImageName.parse("redis:7")) {
    init {
        withExposedPorts(6379)
    }
}

@TestConfiguration(proxyBeanMethods = false)
class TestContainersConfig {
    @Bean
    @ServiceConnection
    fun postgres() = KPostgres()

    @Bean
    @ServiceConnection(name = "redis")
    fun redis() = KRedis()
}

/** Real Postgres and Redis in containers; storage is in memory; the background worker is off so tests drive it. */
@SpringBootTest(properties = ["app.worker.enabled=false", "app.storage.endpoint="])
@Import(TestContainersConfig::class)
abstract class IntegrationTestSupport {
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var redis: StringRedisTemplate
    @Autowired lateinit var storageService: StorageService
    @Autowired lateinit var users: UserRepository

    val storage: InMemoryStorageService get() = storageService as InMemoryStorageService

    @BeforeEach
    fun cleanUp() {
        jdbc.sql("TRUNCATE users, rooms RESTART IDENTITY CASCADE").update()
        redis.connectionFactory!!.connection.use { it.serverCommands().flushAll() }
        storage.objects.clear()
    }

    fun newUser(name: String = "user"): Long =
        users.insertIfAbsent("TEST", name + System.nanoTime(), name)!!

    fun jpeg(color: Color = Color.ORANGE): ByteArray {
        val image = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = color
        g.fillRect(0, 0, 640, 480)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    /** Starts [threads] tasks at the same moment and returns their results (or exceptions). */
    fun <T> concurrently(threads: Int, task: (Int) -> T): List<Result<T>> {
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(1)
        try {
            val futures = (0 until threads).map { i ->
                pool.submit(Callable {
                    ready.await()
                    runCatching { task(i) }
                })
            }
            ready.countDown()
            return futures.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }
}
