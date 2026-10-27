package com.dietshare.analysis

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service

data class PatternSummary(
    val days: Int,
    val mealsByType: Map<String, Int>,
    val lowCounts: Map<String, Int>,
    val highCounts: Map<String, Int>,
) {
    /** Short Korean text for the AI prompt, e.g. "기록 9끼(아침 2, 점심 4, 저녁 3) · 부족: 단백질 5회". */
    fun toPrompt(): String {
        val total = mealsByType.values.sum()
        if (total == 0) return "최근 기록 없음"
        val typeLabels = mapOf("BREAKFAST" to "아침", "LUNCH" to "점심", "DINNER" to "저녁", "SNACK" to "간식")
        val nutrientLabels = mapOf("CARB" to "탄수화물", "PROTEIN" to "단백질", "FAT" to "지방", "VEGETABLE" to "채소")
        val types = mealsByType.entries.joinToString { "${typeLabels[it.key] ?: it.key} ${it.value}" }
        val low = lowCounts.entries.joinToString { "${nutrientLabels[it.key] ?: it.key} ${it.value}회" }
        val high = highCounts.entries.joinToString { "${nutrientLabels[it.key] ?: it.key} ${it.value}회" }
        return buildString {
            append("기록 ${total}끼($types)")
            if (low.isNotEmpty()) append(" · 부족: $low")
            if (high.isNotEmpty()) append(" · 많음: $high")
        }
    }
}

/** Keeps a small 7-day summary per user so prompts stay short and cheap. */
@Service
class PatternSummaryService(private val jdbc: JdbcClient, private val mapper: ObjectMapper) {
    fun refresh(userId: Long) {
        val mealsByType = jdbc.sql(
            """
            SELECT meal_type, count(*) AS cnt FROM meals
            WHERE user_id = :userId AND status = 'DONE' AND taken_at >= now() - interval '7 days'
            GROUP BY meal_type
            """.trimIndent()
        ).param("userId", userId)
            .query(RowMapper { rs, _ -> rs.getString("meal_type") to rs.getInt("cnt") }).list().toMap()

        val levelCounts = jdbc.sql(
            """
            SELECT n.key AS nutrient, n.value AS level, count(*) AS cnt
            FROM meals m
            JOIN meal_analyses a ON a.meal_id = m.id
            CROSS JOIN LATERAL jsonb_each_text(a.nutrients) n
            WHERE m.user_id = :userId AND m.status = 'DONE' AND m.taken_at >= now() - interval '7 days'
              AND n.value IN ('LOW', 'HIGH')
            GROUP BY n.key, n.value
            """.trimIndent()
        ).param("userId", userId)
            .query(RowMapper { rs, _ -> Triple(rs.getString("nutrient"), rs.getString("level"), rs.getInt("cnt")) })
            .list()

        val summary = PatternSummary(
            days = 7,
            mealsByType = mealsByType,
            lowCounts = levelCounts.filter { it.second == "LOW" }.associate { it.first to it.third },
            highCounts = levelCounts.filter { it.second == "HIGH" }.associate { it.first to it.third },
        )

        jdbc.sql(
            """
            INSERT INTO user_pattern_summaries (user_id, summary, updated_at)
            VALUES (:userId, CAST(:summary AS jsonb), now())
            ON CONFLICT (user_id) DO UPDATE SET summary = EXCLUDED.summary, updated_at = now()
            """.trimIndent()
        )
            .param("userId", userId)
            .param("summary", mapper.writeValueAsString(summary))
            .update()
    }

    fun find(userId: Long): PatternSummary? =
        jdbc.sql("SELECT summary FROM user_pattern_summaries WHERE user_id = :userId")
            .param("userId", userId)
            .query(String::class.java).optional()
            .map { mapper.readValue(it, PatternSummary::class.java) }
            .orElse(null)
}
