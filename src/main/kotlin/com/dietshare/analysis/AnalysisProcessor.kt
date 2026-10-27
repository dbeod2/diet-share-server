package com.dietshare.analysis

import com.dietshare.common.AppProperties
import com.dietshare.meal.Meal
import com.dietshare.meal.MealRepository
import com.dietshare.meal.MealStatus
import com.dietshare.quota.QuotaService
import com.dietshare.room.ShareRepository
import com.dietshare.storage.ImageProcessor
import com.dietshare.storage.InvalidImageException
import com.dietshare.storage.StorageKeys
import com.dietshare.storage.StorageService
import com.dietshare.user.GoalType
import com.dietshare.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * Runs one analysis job end to end.
 *
 * The slow parts (download, AI call, upload) run outside any transaction.
 * Every final state change is a conditional UPDATE (status must still be ANALYZING),
 * so a meal deleted by its owner mid-analysis is simply skipped and its new objects removed.
 */
@Component
class AnalysisProcessor(
    private val meals: MealRepository,
    private val shares: ShareRepository,
    private val analyses: AnalysisRepository,
    private val queue: JobQueue,
    private val users: UserRepository,
    private val storage: StorageService,
    private val images: ImageProcessor,
    private val ai: AiAnalyzer,
    private val quota: QuotaService,
    private val patterns: PatternSummaryService,
    private val tx: TransactionTemplate,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun process(job: AnalysisJob) {
        val meal = meals.findById(job.mealId)
        if (meal == null || !meals.markAnalyzing(meal.id)) {
            queue.complete(job.id) // deleted or already handled
            return
        }

        try {
            handle(meal)
            queue.complete(job.id)
        } catch (e: InvalidImageException) {
            log.warn("Meal {} has an unreadable image", meal.id)
            giveUp(job, meal, e)
        } catch (e: Exception) {
            if (job.attempts >= props.worker.maxAttempts) {
                log.warn("Meal {} failed after {} attempts", meal.id, job.attempts, e)
                giveUp(job, meal, e)
            } else {
                log.info("Meal {} attempt {} failed, retrying: {}", meal.id, job.attempts, e.message)
                meals.resetToPending(listOf(meal.id))
                queue.retryLater(job.id, 10L * job.attempts, e.message)
            }
        }
    }

    private fun handle(meal: Meal) {
        val sourceKey = meal.tempKey ?: meal.imageKey ?: throw InvalidImageException("No image for meal ${meal.id}")
        val bytes = storage.read(sourceKey)
        val hash = images.sha256(bytes)

        // Same photo uploaded again: reuse the earlier result and give the quota slot back.
        meals.findDoneByHash(meal.userId, hash, meal.id)?.let { earlier ->
            val (photo, thumb) = storeImage(meal, bytes, sourceKey)
            finishDone(meal, photo, thumb, hash) { analyses.copy(earlier.id, meal.id) }
            quota.refund(meal.userId, meal.quotaDate)
            return
        }

        val user = users.findById(meal.userId) ?: return
        val result = ai.analyze(bytes, context(meal.userId, user.goalType))

        when {
            result.inappropriate -> {
                // Never keep inappropriate photos: drop the upload, leave a tombstone row.
                storage.delete(listOfNotNull(meal.tempKey, meal.imageKey))
                tx.executeWithoutResult {
                    if (meals.reject(meal.id)) shares.deleteAllOf(meal.id)
                }
                quota.refund(meal.userId, meal.quotaDate)
            }

            !result.isFood -> {
                val (photo, thumb) = storeImage(meal, bytes, sourceKey)
                if (!meals.completeWithImage(meal.id, MealStatus.NOT_FOOD, photo, thumb, hash)) {
                    storage.delete(listOf(photo, thumb))
                }
                quota.refund(meal.userId, meal.quotaDate)
            }

            else -> {
                val (photo, thumb) = storeImage(meal, bytes, sourceKey)
                finishDone(meal, photo, thumb, hash) { analyses.insert(meal.id, result, user.goalType) }
                patterns.refresh(meal.userId)
            }
        }
    }

    private fun finishDone(meal: Meal, photo: String, thumb: String, hash: String, saveAnalysis: () -> Unit) {
        val done = tx.execute {
            if (!meals.completeWithImage(meal.id, MealStatus.DONE, photo, thumb, hash)) return@execute false
            saveAnalysis()
            shares.makeVisible(meal.id) // moderation passed: now visible in rooms
            true
        } ?: false
        if (!done) storage.delete(listOf(photo, thumb)) // meal was deleted meanwhile
    }

    /** Writes the photo and its thumbnail to their permanent keys and removes the temp upload. */
    private fun storeImage(meal: Meal, bytes: ByteArray, sourceKey: String): Pair<String, String> {
        val photo = StorageKeys.photo(meal.userId, meal.id)
        val thumb = StorageKeys.thumb(meal.userId, meal.id)
        if (sourceKey != photo) storage.write(photo, bytes, "image/jpeg")
        storage.write(thumb, images.thumbnail(bytes), "image/jpeg")
        meal.tempKey?.let { storage.delete(listOf(it)) }
        return photo to thumb
    }

    /** Out of retries: keep the photo privately so the user can retry later, and refund the slot. */
    private fun giveUp(job: AnalysisJob, meal: Meal, e: Exception) {
        val privateKey = meal.tempKey?.let { temp ->
            runCatching {
                val photo = StorageKeys.photo(meal.userId, meal.id)
                storage.copy(temp, photo)
                storage.delete(listOf(temp))
                photo
            }.getOrNull()
        }
        meals.markFailed(meal.id, privateKey)
        quota.refund(meal.userId, meal.quotaDate)
        queue.fail(job.id, e.message)
    }

    private fun context(userId: Long, goalType: GoalType): AnalysisContext {
        val profile = users.findProfile(userId)
        return AnalysisContext(
            goalType = goalType,
            recentPattern = patterns.find(userId)?.toPrompt(),
            likedFoods = profile?.likedFoods.orEmpty(),
            dislikedFoods = profile?.dislikedFoods.orEmpty(),
            dietType = profile?.dietType,
        )
    }
}
