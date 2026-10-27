package com.dietshare.analysis

import com.dietshare.user.GoalType
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

data class MealAnalysis(
    val mealId: Long,
    val foods: List<String>,
    val grade: Grade,
    val nutrients: Map<Nutrient, Level>,
    val feedback: String?,
)

@Repository
class AnalysisRepository(private val jdbc: JdbcClient, private val mapper: ObjectMapper) {
    private val listType = object : TypeReference<List<String>>() {}
    private val nutrientType = object : TypeReference<Map<Nutrient, Level>>() {}

    private val analysisMapper = RowMapper { rs, _ ->
        MealAnalysis(
            mealId = rs.getLong("meal_id"),
            foods = mapper.readValue(rs.getString("foods"), listType),
            grade = Grade.valueOf(rs.getString("grade")),
            nutrients = mapper.readValue(rs.getString("nutrients"), nutrientType),
            feedback = rs.getString("feedback"),
        )
    }

    fun insert(mealId: Long, result: AiResult, goalType: GoalType) {
        jdbc.sql(
            """
            INSERT INTO meal_analyses (meal_id, foods, grade, nutrients, feedback, goal_type, model)
            VALUES (:mealId, CAST(:foods AS jsonb), :grade, CAST(:nutrients AS jsonb), :feedback, :goal, :model)
            ON CONFLICT (meal_id) DO NOTHING
            """.trimIndent()
        )
            .param("mealId", mealId)
            .param("foods", mapper.writeValueAsString(result.foods))
            .param("grade", requireNotNull(result.grade).name)
            .param("nutrients", mapper.writeValueAsString(result.nutrients))
            .param("feedback", result.feedback)
            .param("goal", goalType.name)
            .param("model", result.model)
            .update()
    }

    /** Duplicate photo: reuse the earlier result instead of calling the AI again. */
    fun copy(fromMealId: Long, toMealId: Long) {
        jdbc.sql(
            """
            INSERT INTO meal_analyses (meal_id, foods, grade, nutrients, feedback, goal_type, model)
            SELECT :toId, foods, grade, nutrients, feedback, goal_type, model
            FROM meal_analyses WHERE meal_id = :fromId
            ON CONFLICT (meal_id) DO NOTHING
            """.trimIndent()
        )
            .param("fromId", fromMealId)
            .param("toId", toMealId)
            .update()
    }

    fun find(mealId: Long): MealAnalysis? =
        jdbc.sql("SELECT * FROM meal_analyses WHERE meal_id = :mealId").param("mealId", mealId)
            .query(analysisMapper).optional().orElse(null)

    fun findAll(mealIds: Collection<Long>): Map<Long, MealAnalysis> {
        if (mealIds.isEmpty()) return emptyMap()
        return jdbc.sql("SELECT * FROM meal_analyses WHERE meal_id IN (:ids)").param("ids", mealIds)
            .query(analysisMapper).list().associateBy { it.mealId }
    }
}
