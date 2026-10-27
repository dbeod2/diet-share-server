package com.dietshare.analysis

import com.dietshare.common.AppProperties
import com.dietshare.meal.MealRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * In-process worker. On the free Render plan it runs inside the API instance;
 * later the same code can run as its own process with WORKER_ENABLED=true and the API with false.
 */
@Component
@ConditionalOnProperty(name = ["app.worker.enabled"], havingValue = "true", matchIfMissing = true)
class AnalysisWorker(
    private val queue: JobQueue,
    private val processor: AnalysisProcessor,
    private val meals: MealRepository,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.worker.poll-delay-ms:2000}", initialDelay = 5000)
    fun poll() {
        repeat(props.worker.batchSize) {
            val job = try {
                queue.claim()
            } catch (e: Exception) {
                log.error("Failed to claim a job", e)
                return
            } ?: return
            processor.process(job)
        }
    }

    /** Puts back jobs whose worker stopped mid-run (deploys, crashes, instance sleeping). */
    @Scheduled(fixedDelay = 60_000, initialDelay = 10_000)
    fun releaseStuckJobs() {
        val mealIds = queue.releaseStuck(props.worker.stuckAfterMinutes)
        if (mealIds.isNotEmpty()) {
            meals.resetToPending(mealIds)
            log.info("Released {} stuck jobs", mealIds.size)
        }
    }

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    fun cleanUp() {
        val deleted = queue.deleteFinished(props.worker.keepDoneDays)
        if (deleted > 0) log.info("Deleted {} finished jobs", deleted)
    }
}
