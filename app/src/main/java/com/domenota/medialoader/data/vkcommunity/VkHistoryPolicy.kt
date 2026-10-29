package com.domenota.medialoader.data.vkcommunity

import java.time.LocalDate

object VkHistoryPolicy {
    fun canDelete(phase: VkJobPhase): Boolean =
        phase != VkJobPhase.SCANNING && phase != VkJobPhase.DOWNLOADING

    fun repeatPeriod(job: VkCommunityJob): VkDatePeriod =
        VkDatePeriod.of(LocalDate.parse(job.startDate), LocalDate.parse(job.endDate))
}
