package com.dietshare.analysis

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

data class AnalysisJob(val id: Long, val mealId: Long, val attempts: Int)

/**
 * The analysis_jobs table used as a work queue.
 * Producers insert in the same transaction as the meal, so a saved meal never loses its job.
 * Workers claim with FOR UPDATE SKIP LOCKED, so several workers never take the same job.
 */
@Repository
class JobQueue(private val jdbc: JdbcClient) {
    private val jobMapper = RowMapper { rs, _ ->
        AnalysisJob(rs.getLong("id"), rs.getLong("meal_id"), rs.getInt("attempts"))
    }

    fun enqueue(mealId: Long) {
        jdbc.sql("INSERT INTO analysis_jobs (meal_id) VALUES (:mealId)").param("mealId", mealId).update()
    }

    /** Claims the oldest runnable job, or returns null when the queue is empty. */
    fun claim(): AnalysisJob? =
        jdbc.sql(
            """
            UPDATE analysis_jobs
            SET status = 'RUNNING', locked_at = now(), attempts = attempts + 1
            WHERE id = (
                SELECT id FROM analysis_jobs
                WHERE status = 'PENDING' AND run_after <= now()
                ORDER BY id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            RETURNING id, meal_id, attempts
            """.trimIndent()
        ).query(jobMapper).optional().orElse(null)

    fun complete(jobId: Long) {
        jdbc.sql("UPDATE analysis_jobs SET status = 'DONE', locked_at = NULL WHERE id = :id")
            .param("id", jobId).update()
    }

    fun retryLater(jobId: Long, delaySeconds: Long, error: String?) {
        jdbc.sql(
            """
            UPDATE analysis_jobs
            SET status = 'PENDING', locked_at = NULL, last_error = :error,
                run_after = now() + make_interval(secs => :delay)
            WHERE id = :id
            """.trimIndent()
        )
            .param("id", jobId)
            .param("delay", delaySeconds)
            .param("error", error?.take(1000))
            .update()
    }

    fun fail(jobId: Long, error: String?) {
        jdbc.sql("UPDATE analysis_jobs SET status = 'FAILED', locked_at = NULL, last_error = :error WHERE id = :id")
            .param("id", jobId)
            .param("error", error?.take(1000))
            .update()
    }

    /** Jobs whose worker died mid-run go back to the queue. Returns their meal ids. */
    fun releaseStuck(stuckAfterMinutes: Int): List<Long> =
        jdbc.sql(
            """
            UPDATE analysis_jobs
            SET status = 'PENDING', locked_at = NULL, last_error = 'released after timeout'
            WHERE status = 'RUNNING' AND locked_at < now() - make_interval(mins => :minutes)
            RETURNING meal_id
            """.trimIndent()
        )
            .param("minutes", stuckAfterMinutes)
            .query(java.lang.Long::class.java).list().map { it.toLong() }

    fun deleteFinished(olderThanDays: Int): Int =
        jdbc.sql(
            """
            DELETE FROM analysis_jobs
            WHERE status IN ('DONE', 'FAILED') AND created_at < now() - make_interval(days => :days)
            """.trimIndent()
        )
            .param("days", olderThanDays)
            .update()
}
