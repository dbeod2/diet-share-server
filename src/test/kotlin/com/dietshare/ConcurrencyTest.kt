package com.dietshare

import com.dietshare.analysis.JobQueue
import com.dietshare.common.ApiException
import com.dietshare.meal.MealRepository
import com.dietshare.meal.MealStatus
import com.dietshare.meal.MealType
import com.dietshare.quota.QuotaService
import com.dietshare.room.RoomRepository
import com.dietshare.room.RoomService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConcurrencyTest : IntegrationTestSupport() {
    @Autowired lateinit var quota: QuotaService
    @Autowired lateinit var roomService: RoomService
    @Autowired lateinit var rooms: RoomRepository
    @Autowired lateinit var queue: JobQueue
    @Autowired lateinit var meals: MealRepository

    @Test
    fun `30 simultaneous reservations never exceed the daily limit of 10`() {
        val userId = newUser()

        val results = concurrently(30) { quota.tryReserve(userId) }

        assertEquals(10, results.count { it.getOrThrow() })
        assertEquals(10, quota.status(userId).used)
    }

    @Test
    fun `refund gives a slot back`() {
        val userId = newUser()
        repeat(10) { quota.tryReserve(userId) }

        quota.refund(userId, quota.today())

        assertTrue(quota.tryReserve(userId))
    }

    @Test
    fun `10 people joining a 5-seat room at once leave it exactly full`() {
        val owner = newUser("owner")
        val room = roomService.create(owner, "헬스 메이트")
        val joiners = (1..10).map { newUser("joiner$it") }

        val results = concurrently(joiners.size) { i -> roomService.join(joiners[i], room.inviteCode) }

        val full = results.count { (it.exceptionOrNull() as? ApiException)?.code == "ROOM_FULL" }
        assertEquals(4, results.count { it.isSuccess })
        assertEquals(6, full)
        assertEquals(5, rooms.findById(room.id)!!.memberCount)
        assertEquals(5, rooms.members(room.id).size)
    }

    @Test
    fun `joining twice does not take a second seat`() {
        val owner = newUser("owner")
        val member = newUser("member")
        val room = roomService.create(owner, "점심팟")

        roomService.join(member, room.inviteCode)
        roomService.join(member, room.inviteCode)

        assertEquals(2, rooms.findById(room.id)!!.memberCount)
    }

    @Test
    fun `owner leaving hands the room to the earliest member, last one out deletes it`() {
        val owner = newUser("owner")
        val first = newUser("first")
        val second = newUser("second")
        val room = roomService.create(owner, "방")
        roomService.join(first, room.inviteCode)
        roomService.join(second, room.inviteCode)

        roomService.leave(owner, room.id)
        assertEquals(first, rooms.findById(room.id)!!.ownerId)
        assertEquals(2, rooms.findById(room.id)!!.memberCount)

        roomService.leave(first, room.id)
        roomService.leave(second, room.id)
        assertEquals(null, rooms.findById(room.id))
    }

    @Test
    fun `four workers claiming at once never take the same job twice`() {
        val userId = newUser()
        val mealIds = (1..40).map { i ->
            meals.insert(userId, "req-$i", MealType.LUNCH, Instant.now(), MealStatus.PENDING, "temp/$userId/$i.jpg", null)
                .also { queue.enqueue(it) }
        }

        val claimed = concurrently(4) {
            generateSequence { queue.claim() }.map { it.mealId }.toList()
        }.flatMap { it.getOrThrow() }

        assertEquals(mealIds.size, claimed.size)
        assertEquals(mealIds.toSet(), claimed.toSet())
    }
}
