package com.dietshare.user

import com.dietshare.common.instant
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant

enum class GoalType { DIET, STAMINA, MUSCLE, HEALTH }

data class User(
    val id: Long,
    val provider: String,
    val providerUserId: String,
    val nickname: String,
    val goalType: GoalType,
    val createdAt: Instant,
)

data class UserProfile(
    val likedFoods: List<String> = emptyList(),
    val dislikedFoods: List<String> = emptyList(),
    val dietType: String? = null,
    val heightCm: Int? = null,
    val weightKg: BigDecimal? = null,
)

@Repository
class UserRepository(private val jdbc: JdbcClient, private val mapper: ObjectMapper) {
    private val userMapper = RowMapper { rs, _ ->
        User(
            id = rs.getLong("id"),
            provider = rs.getString("provider"),
            providerUserId = rs.getString("provider_user_id"),
            nickname = rs.getString("nickname"),
            goalType = GoalType.valueOf(rs.getString("goal_type")),
            createdAt = rs.instant("created_at"),
        )
    }

    private val stringList = object : TypeReference<List<String>>() {}

    fun findById(id: Long): User? =
        jdbc.sql("SELECT * FROM users WHERE id = :id")
            .param("id", id)
            .query(userMapper).optional().orElse(null)

    fun findByProvider(provider: String, providerUserId: String): User? =
        jdbc.sql("SELECT * FROM users WHERE provider = :provider AND provider_user_id = :pid")
            .param("provider", provider)
            .param("pid", providerUserId)
            .query(userMapper).optional().orElse(null)

    /** Returns null when another request created the same user first. */
    fun insertIfAbsent(provider: String, providerUserId: String, nickname: String): Long? =
        jdbc.sql(
            """
            INSERT INTO users (provider, provider_user_id, nickname)
            VALUES (:provider, :pid, :nickname)
            ON CONFLICT (provider, provider_user_id) DO NOTHING
            RETURNING id
            """.trimIndent()
        )
            .param("provider", provider)
            .param("pid", providerUserId)
            .param("nickname", nickname)
            .query(java.lang.Long::class.java).optional().map { it.toLong() }.orElse(null)

    fun update(id: Long, nickname: String?, goalType: GoalType?) {
        jdbc.sql(
            """
            UPDATE users
            SET nickname = COALESCE(:nickname, nickname),
                goal_type = COALESCE(:goal, goal_type),
                updated_at = now()
            WHERE id = :id
            """.trimIndent()
        )
            .param("id", id)
            .param("nickname", nickname)
            .param("goal", goalType?.name)
            .update()
    }

    fun delete(id: Long) {
        jdbc.sql("DELETE FROM users WHERE id = :id").param("id", id).update()
    }

    fun findProfile(userId: Long): UserProfile? =
        jdbc.sql("SELECT * FROM user_profiles WHERE user_id = :id")
            .param("id", userId)
            .query(RowMapper { rs, _ ->
                UserProfile(
                    likedFoods = mapper.readValue(rs.getString("liked_foods"), stringList),
                    dislikedFoods = mapper.readValue(rs.getString("disliked_foods"), stringList),
                    dietType = rs.getString("diet_type"),
                    heightCm = rs.getObject("height_cm") as Int?,
                    weightKg = rs.getBigDecimal("weight_kg"),
                )
            }).optional().orElse(null)

    fun upsertProfile(userId: Long, profile: UserProfile) {
        jdbc.sql(
            """
            INSERT INTO user_profiles (user_id, liked_foods, disliked_foods, diet_type, height_cm, weight_kg, updated_at)
            VALUES (:id, CAST(:liked AS jsonb), CAST(:disliked AS jsonb), :dietType, :height, :weight, now())
            ON CONFLICT (user_id) DO UPDATE SET
                liked_foods = EXCLUDED.liked_foods,
                disliked_foods = EXCLUDED.disliked_foods,
                diet_type = EXCLUDED.diet_type,
                height_cm = EXCLUDED.height_cm,
                weight_kg = EXCLUDED.weight_kg,
                updated_at = now()
            """.trimIndent()
        )
            .param("id", userId)
            .param("liked", mapper.writeValueAsString(profile.likedFoods))
            .param("disliked", mapper.writeValueAsString(profile.dislikedFoods))
            .param("dietType", profile.dietType)
            .param("height", profile.heightCm)
            .param("weight", profile.weightKg)
            .update()
    }
}
