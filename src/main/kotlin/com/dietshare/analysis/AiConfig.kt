package com.dietshare.analysis

import com.dietshare.common.AppProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
class AiConfig {
    @Bean
    fun aiAnalyzer(props: AppProperties, builder: RestClient.Builder, mapper: ObjectMapper): AiAnalyzer =
        when (props.ai.provider.lowercase()) {
            "anthropic" -> {
                require(props.ai.apiKey.isNotBlank()) { "AI_API_KEY is required when AI_PROVIDER=anthropic" }
                require(props.ai.model.isNotBlank()) { "AI_MODEL is required when AI_PROVIDER=anthropic" }
                AnthropicAiAnalyzer(builder, mapper, props.ai.apiKey, props.ai.model)
            }
            else -> StubAiAnalyzer()
        }
}
