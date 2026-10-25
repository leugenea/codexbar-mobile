package io.github.leugenea.codexbarmobile

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.leugenea.codexbarmobile.history.HistoryPartition
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Only WorkManager persists enrollment; its input contains one opaque local lifetime, no secrets. */
internal class PeriodicUsageRefresh(private val manager: WorkManager) {
    fun enrollment(target: BackgroundRefreshTarget?) {
        if (target == null) manager.cancelAllWorkByTag(TAG)
        else manager.enqueueUniquePeriodicWork(name(target), ExistingPeriodicWorkPolicy.KEEP, request(target))
    }

    companion object {
        const val TAG = "periodic-usage-refresh"
        const val LIFETIME = "credential-lifetime"
        const val INTERVAL_MINUTES = 15L
        const val RECENT_SUCCESS_MILLIS = 900_000L

        fun name(target: BackgroundRefreshTarget) = "$TAG:${target.partition.value}"

        fun request(target: BackgroundRefreshTarget): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<PeriodicUsageRefreshWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setInputData(workDataOf(LIFETIME to target.partition.value.toString()))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true).build())
                .setInitialDelay(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()

        fun target(value: String?): BackgroundRefreshTarget? {
            if (value == null) return null
            val uuid = try { UUID.fromString(value) } catch (_: IllegalArgumentException) { return null }
            return uuid.takeIf { it.toString() == value }?.let { BackgroundRefreshTarget(HistoryPartition(it)) }
        }
    }
}

/** Ordinary bounded work: cancellation escapes, expected errors wait for the next period. */
class PeriodicUsageRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val target = PeriodicUsageRefresh.target(inputData.getString(PeriodicUsageRefresh.LIFETIME))
            ?: return Result.failure()
        val outcome = NativeConnection.get(applicationContext).refreshInBackground(
            target, minimumSuccessAgeMillis = PeriodicUsageRefresh.RECENT_SUCCESS_MILLIS)
        if (outcome is BackgroundRefreshOutcome.Rejected && outcome.reason == BackgroundRefreshRejection.STALE_LIFETIME) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(PeriodicUsageRefresh.name(target))
        }
        // This acknowledges this scheduler opportunity, NOT endpoint or history success.
        // No Result.retry(): offline, Retry-After and transient failures must not create a 30s loop.
        return Result.success()
    }
}
