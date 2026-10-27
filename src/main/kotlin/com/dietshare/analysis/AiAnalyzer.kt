package com.dietshare.analysis

import com.dietshare.user.GoalType

enum class Grade { GOOD, OK, POOR }

enum class Nutrient { CARB, PROTEIN, FAT, VEGETABLE }

enum class Level { LOW, OK, HIGH }

/** What the model gets besides the photo. No user identifiers are ever sent. */
data class AnalysisContext(
    val goalType: GoalType,
    val recentPattern: String?,
    val likedFoods: List<String> = emptyList(),
    val dislikedFoods: List<String> = emptyList(),
    val dietType: String? = null,
)

data class AiResult(
    val isFood: Boolean,
    val inappropriate: Boolean,
    val foods: List<String> = emptyList(),
    val grade: Grade? = null,
    val nutrients: Map<Nutrient, Level> = emptyMap(),
    val feedback: String? = null,
    val model: String,
)

/** Thrown for failures worth retrying (timeouts, 5xx, unparsable output). */
class AiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

interface AiAnalyzer {
    fun analyze(image: ByteArray, context: AnalysisContext): AiResult
}

/** Default for local runs and the first beta deploy: no API key needed, always returns the same shape. */
class StubAiAnalyzer : AiAnalyzer {
    override fun analyze(image: ByteArray, context: AnalysisContext): AiResult {
        val feedback = when (context.goalType) {
            GoalType.DIET -> "튀김 대신 채소를 하나 더해보세요."
            GoalType.STAMINA -> "탄수화물이 적당해서 운동 전 식사로 좋아요."
            GoalType.MUSCLE -> "단백질이 조금 부족해요. 달걀이나 두부를 더해보세요."
            GoalType.HEALTH -> "채소가 조금 부족해요. 다음 끼니에 나물을 곁들여 보세요."
        }
        return AiResult(
            isFood = true,
            inappropriate = false,
            foods = listOf("현미밥", "계란말이", "시금치나물"),
            grade = Grade.OK,
            nutrients = mapOf(
                Nutrient.CARB to Level.OK,
                Nutrient.PROTEIN to Level.LOW,
                Nutrient.FAT to Level.OK,
                Nutrient.VEGETABLE to Level.LOW,
            ),
            feedback = feedback,
            model = "stub",
        )
    }
}
