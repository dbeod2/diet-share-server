package com.dietshare.user

import com.dietshare.common.ApiException
import com.dietshare.meal.MealService
import com.dietshare.room.RoomService
import org.springframework.stereotype.Service
import kotlin.random.Random

@Service
class UserService(
    private val users: UserRepository,
    private val roomService: RoomService,
    private val mealService: MealService,
) {
    /** Returns the user and whether it was just created. Safe against two concurrent first logins. */
    fun findOrCreate(provider: String, providerUserId: String): Pair<User, Boolean> {
        users.findByProvider(provider, providerUserId)?.let { return it to false }
        val nickname = "식단러" + Random.nextInt(1000, 10000)
        val createdId = users.insertIfAbsent(provider, providerUserId, nickname)
        val user = users.findByProvider(provider, providerUserId)
            ?: throw IllegalStateException("User missing right after insert")
        return user to (createdId != null)
    }

    fun get(userId: Long): User =
        users.findById(userId) ?: throw ApiException.unauthorized("계정을 찾을 수 없어요")

    fun update(userId: Long, nickname: String?, goalType: GoalType?): User {
        users.update(userId, nickname?.trim(), goalType)
        return get(userId)
    }

    fun profile(userId: Long): UserProfile = users.findProfile(userId) ?: UserProfile()

    fun updateProfile(userId: Long, profile: UserProfile): UserProfile {
        users.upsertProfile(userId, profile)
        return profile(userId)
    }

    /**
     * Account deletion: leave every room (ownership moves to the next member),
     * delete all photos from storage, then delete the user row (meals, shares and reactions cascade).
     */
    fun delete(userId: Long) {
        roomService.leaveAllRooms(userId)
        mealService.deleteAllForUser(userId)
        users.delete(userId)
    }
}
