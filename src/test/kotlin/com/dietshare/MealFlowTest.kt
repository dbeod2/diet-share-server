package com.dietshare

import com.dietshare.analysis.AnalysisProcessor
import com.dietshare.analysis.AnalysisRepository
import com.dietshare.analysis.JobQueue
import com.dietshare.meal.CreateMealRequest
import com.dietshare.meal.MealService
import com.dietshare.meal.MealStatus
import com.dietshare.quota.QuotaService
import com.dietshare.room.FeedService
import com.dietshare.room.ReactionType
import com.dietshare.room.ReportReason
import com.dietshare.room.RoomService
import com.dietshare.room.SocialService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MealFlowTest : IntegrationTestSupport() {
    @Autowired lateinit var mealService: MealService
    @Autowired lateinit var processor: AnalysisProcessor
    @Autowired lateinit var queue: JobQueue
    @Autowired lateinit var analyses: AnalysisRepository
    @Autowired lateinit var quota: QuotaService
    @Autowired lateinit var roomService: RoomService
    @Autowired lateinit var feedService: FeedService
    @Autowired lateinit var socialService: SocialService

    private fun upload(userId: Long): String {
        val key = mealService.createUploadUrl(userId).key
        storage.write(key, jpeg(), "image/jpeg")
        return key
    }

    private fun runWorker() {
        generateSequence { queue.claim() }.forEach { processor.process(it) }
    }

    @Test
    fun `upload, analyse and share - visible in the room only after analysis`() {
        val me = newUser("me")
        val friend = newUser("friend")
        val room = roomService.create(me, "헬스 메이트")
        roomService.join(friend, room.inviteCode)

        val created = mealService.create(me, CreateMealRequest("req-1", upload(me), roomIds = listOf(room.id)))
        assertEquals(MealStatus.PENDING, created.status)
        assertTrue(feedService.feed(friend, room.id, null, 10).items.isEmpty(), "hidden before moderation")

        runWorker()

        val done = mealService.detail(me, created.id)
        assertEquals(MealStatus.DONE, done.status)
        assertNotNull(analyses.find(created.id))
        assertTrue(storage.exists("photos/$me/${created.id}.jpg"))
        assertTrue(storage.exists("thumbs/$me/${created.id}.jpg"))
        assertTrue(storage.objects.keys.none { it.startsWith("temp/") }, "temp upload removed")

        val feed = feedService.feed(friend, room.id, null, 10)
        assertEquals(listOf(created.id), feed.items.map { it.mealId })
        assertNotNull(feed.items.first().grade)
    }

    @Test
    fun `retrying the same request returns the same meal and uses one quota slot`() {
        val me = newUser()
        val key = upload(me)

        val results = concurrently(5) { mealService.create(me, CreateMealRequest("same-request", key)) }

        assertEquals(1, results.map { it.getOrThrow().id }.toSet().size)
        assertEquals(1, quota.status(me).used)
    }

    @Test
    fun `the same photo uploaded twice reuses the analysis and refunds the second slot`() {
        val me = newUser()
        mealService.create(me, CreateMealRequest("first", upload(me)))
        runWorker()

        val second = mealService.create(me, CreateMealRequest("second", upload(me)))
        runWorker()

        assertEquals(MealStatus.DONE, mealService.detail(me, second.id).status)
        assertEquals(1, quota.status(me).used)
    }

    @Test
    fun `over the daily limit the meal is kept privately without analysis`() {
        val me = newUser()
        repeat(10) { quota.tryReserve(me) }

        val limited = mealService.create(me, CreateMealRequest("over", upload(me)))

        assertEquals(MealStatus.LIMITED, limited.status)
        assertNull(queue.claim())
        assertTrue(storage.exists("photos/$me/${limited.id}.jpg"))
    }

    @Test
    fun `reactions are idempotent and two reports hide a meal from the room`() {
        val me = newUser("me")
        val a = newUser("a")
        val b = newUser("b")
        val room = roomService.create(me, "방")
        roomService.join(a, room.inviteCode)
        roomService.join(b, room.inviteCode)
        val meal = mealService.create(me, CreateMealRequest("r", upload(me), roomIds = listOf(room.id)))
        runWorker()

        socialService.react(a, room.id, meal.id, ReactionType.LIKE)
        socialService.react(a, room.id, meal.id, ReactionType.LIKE)
        assertEquals(1, feedService.feed(me, room.id, null, 10).items.first().reactions.first().count)

        socialService.report(a, room.id, meal.id, ReportReason.INAPPROPRIATE)
        assertTrue(feedService.feed(a, room.id, null, 10).items.isEmpty(), "hidden for the reporter")
        assertEquals(1, feedService.feed(b, room.id, null, 10).items.size, "still visible to others")

        socialService.report(b, room.id, meal.id, ReportReason.INAPPROPRIATE)
        assertTrue(feedService.feed(me, room.id, null, 10).items.isEmpty(), "hidden for everyone")
    }

    @Test
    fun `leaving a room removes my meals from it`() {
        val me = newUser("me")
        val owner = newUser("owner")
        val room = roomService.create(owner, "방")
        roomService.join(me, room.inviteCode)
        mealService.create(me, CreateMealRequest("r", upload(me), roomIds = listOf(room.id)))
        runWorker()

        roomService.leave(me, room.id)

        assertTrue(feedService.feed(owner, room.id, null, 10).items.isEmpty())
    }
}
