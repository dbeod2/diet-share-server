package com.dietshare.storage

import com.dietshare.common.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class StorageConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun storageService(props: AppProperties, clock: Clock): StorageService =
        if (props.storage.endpoint.isBlank()) {
            log.warn("STORAGE_ENDPOINT is empty: using in-memory storage. Do not use this in production.")
            InMemoryStorageService(clock)
        } else {
            S3StorageService(props.storage, clock)
        }
}
