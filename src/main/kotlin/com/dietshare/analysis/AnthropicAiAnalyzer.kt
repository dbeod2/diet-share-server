package com.dietshare.analysis

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.util.Base64

/**
 * Calls the Anthropic Messages API with the photo and asks for a fixed JSON shape.
 * Set AI_PROVIDER=anthropic, AI_API_KEY and AI_MODEL (check the current model list before deploying).
 */
class AnthropicAiAnalyzer(
    builder: RestClient.Builder,
    private val mapper: ObjectMapper,
    apiKey: String,
    private val model: String,
) : AiAnalyzer {
    private val client = builder
        .baseUrl("https://api.anthropic.com")
        .defaultHeader("x-api-key", apiKey)
        .defaultHeader("anthropic-version", "2023-06-01")
        .build()

    private val systemPrompt = """
        당신은 식단 사진을 보고 영양 균형을 알려주는 도우미입니다.
        반드시 아래 JSON 하나만 출력하세요. 다른 글은 쓰지 마세요.
        {
          "isFood": boolean,                // 사진에 음식이 보이는지
          "inappropriate": boolean,         // 선정적·폭력적·혐오스러운 사진인지
          "foods": [string],                // 인식한 음식 이름(한국어), 최대 6개
          "grade": "GOOD" | "OK" | "POOR",  // 목표 기준 영양 균형
          "nutrients": {"CARB": "LOW"|"OK"|"HIGH", "PROTEIN": ..., "FAT": ..., "VEGETABLE": ...},
          "feedback": string                // 한국어 한 문장, 60자 이내, 다정한 존댓말
        }
        규칙:
        - 칼로리나 그램 같은 숫자는 쓰지 마세요.
        - 진단, 치료, 질병에 대한 조언은 하지 마세요.
        - 음식이 아니거나 부적절하면 foods는 빈 배열, grade/nutrients/feedback은 null로 두세요.
    """.trimIndent()

    override fun analyze(image: ByteArray, context: AnalysisContext): AiResult {
        val body = mapOf(
            "model" to model,
            "max_tokens" to 400,
            "system" to systemPrompt,
            "messages" to listOf(
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf(
                            "type" to "image",
                            "source" to mapOf(
                                "type" to "base64",
                                "media_type" to "image/jpeg",
                                "data" to Base64.getEncoder().encodeToString(image),
                            ),
                        ),
                        mapOf("type" to "text", "text" to userPrompt(context)),
                    ),
                )
            ),
        )

        val response = try {
            client.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode::class.java)
        } catch (e: RestClientException) {
            throw AiException("AI request failed", e)
        } ?: throw AiException("Empty AI response")

        val text = response.path("content").firstOrNull { it.path("type").asText() == "text" }
            ?.path("text")?.asText()
            ?: throw AiException("No text block in AI response")
        return parse(text)
    }

    private fun userPrompt(context: AnalysisContext): String = buildString {
        append("사용자 목표: ").append(goalLabel(context.goalType)).append('\n')
        context.dietType?.let { append("식단 유형: ").append(it).append('\n') }
        if (context.likedFoods.isNotEmpty()) append("좋아하는 음식: ").append(context.likedFoods.joinToString()).append('\n')
        if (context.dislikedFoods.isNotEmpty()) append("싫어하는 음식(제안하지 말 것): ").append(context.dislikedFoods.joinToString()).append('\n')
        context.recentPattern?.let { append("최근 7일 패턴: ").append(it).append('\n') }
        append("이 사진을 분석해 주세요.")
    }

    private fun goalLabel(goal: com.dietshare.user.GoalType) = when (goal) {
        com.dietshare.user.GoalType.DIET -> "다이어트 (과식, 당류, 야식 주의)"
        com.dietshare.user.GoalType.STAMINA -> "체력 향상 (적정 탄수화물, 끼니 거르지 않기)"
        com.dietshare.user.GoalType.MUSCLE -> "근육 증진 (끼니별 단백질 충분히)"
        com.dietshare.user.GoalType.HEALTH -> "건강 유지 (채소와 영양소의 다양성)"
    }

    internal fun parse(text: String): AiResult {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw AiException("AI output has no JSON object")
        val json = try {
            mapper.readTree(text.substring(start, end + 1))
        } catch (e: Exception) {
            throw AiException("AI output is not JSON", e)
        }
        val isFood = json.path("isFood").asBoolean(false)
        val inappropriate = json.path("inappropriate").asBoolean(false)
        if (!isFood || inappropriate) {
            return AiResult(isFood = isFood, inappropriate = inappropriate, model = model)
        }
        val grade = runCatching { Grade.valueOf(json.path("grade").asText()) }.getOrNull()
            ?: throw AiException("Missing grade")
        val nutrients = Nutrient.entries.associateWith { nutrient ->
            runCatching { Level.valueOf(json.path("nutrients").path(nutrient.name).asText()) }.getOrDefault(Level.OK)
        }
        return AiResult(
            isFood = true,
            inappropriate = false,
            foods = json.path("foods").map { it.asText() }.filter { it.isNotBlank() }.take(6),
            grade = grade,
            nutrients = nutrients,
            feedback = json.path("feedback").asText(null)?.take(80),
            model = model,
        )
    }
}
