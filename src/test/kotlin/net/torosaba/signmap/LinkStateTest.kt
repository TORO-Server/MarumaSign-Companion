package net.torosaba.signmap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 状態の移り変わり: 何をもって BROKEN にするか */
class LinkStateTest {

    private val cfg = LinkSettings()

    @Test
    fun newUrlIsPendingAndDueAtOnce() {
        val s = LinkState.pending(T0)
        assertEquals(LinkStatus.PENDING, s.status)
        assertEquals(0L, s.nextCheckAt)
        assertFalse(s.needsAttention())
    }

    @Test
    fun okRecordsTimeAndRechecksInAboutSevenDays() {
        val s = LinkState.pending(T0).apply(ok(), T0, cfg, 0.0)
        assertEquals(LinkStatus.OK, s.status)
        assertEquals(T0, s.lastOkAt)
        assertEquals(T0, s.lastCheckedAt)
        assertEquals(T0 + 7 * D, s.nextCheckAt)
        assertEquals(0, s.consecutiveFailures)
        // ゆらぎは ±10%
        assertEquals(T0 + 7 * D + 7 * D / 10, LinkState.pending(T0).apply(ok(), T0, cfg, 1.0).nextCheckAt)
    }

    @Test
    fun definiteFailureIsBrokenAtOnceAndRechecksDaily() {
        for (r in arrayOf(LinkReason.HTTP_404, LinkReason.HTTP_410, LinkReason.DNS_FAILED, LinkReason.CONNECT_REFUSED,
            LinkReason.TLS_ERROR, LinkReason.HTML_INSTEAD_OF_MEDIA, LinkReason.REMOVED_PLACEHOLDER, LinkReason.REDIRECT_LOOP)) {
            val s = LinkState.pending(T0).apply(broken(r), T0, cfg, 0.0)
            assertEquals(LinkStatus.BROKEN, s.status, r.name)
            assertEquals(r, s.reason)
            assertEquals(T0, s.firstFailedAt)
            assertEquals(T0 + D, s.nextCheckAt)
        }
    }

    @Test
    fun firstFailedAtKeepsTheStartOfTheOutage() {
        var s = LinkState.pending(T0).apply(broken(LinkReason.HTTP_404), T0, cfg, 0.0)
        s = s.apply(broken(LinkReason.HTTP_404), T0 + 3 * D, cfg, 0.0)
        assertEquals(T0, s.firstFailedAt)
        assertEquals(T0 + 3 * D, s.lastFailedAt)
        assertEquals(2, s.consecutiveFailures)
    }

    @Test
    fun brokenRecoversToOkAndForgetsTheFailure() {
        var s = LinkState.pending(T0).apply(broken(LinkReason.HTTP_404), T0, cfg, 0.0)
        s = s.apply(ok(), T0 + D, cfg, 0.0)
        assertEquals(LinkStatus.OK, s.status)
        assertEquals(0L, s.firstFailedAt)
        assertEquals(0, s.consecutiveFailures)
        assertEquals(T0 + D, s.lastOkAt)
        // その後の最初の失敗は、また新しい「始まり」
        s = s.apply(broken(LinkReason.HTTP_404), T0 + 9 * D, cfg, 0.0)
        assertEquals(T0 + 9 * D, s.firstFailedAt)
    }

    @Test
    fun transientFailureBecomesBrokenOnlyAfterThreeFailuresAnd24Hours() {
        var s = LinkState.pending(T0).apply(ok(), T0, cfg, 0.0)
        s = s.apply(transientFailure(), T0 + 1 * H, cfg, 0.0)
        assertEquals(LinkStatus.UNKNOWN, s.status)
        assertEquals(1, s.consecutiveFailures)
        assertEquals(T0 + 1 * H + 6 * H, s.nextCheckAt, "1 回目の失敗のあとは 6 時間後")
        s = s.apply(transientFailure(), T0 + 7 * H, cfg, 0.0)
        assertEquals(LinkStatus.UNKNOWN, s.status)
        assertEquals(T0 + 7 * H + 12 * H, s.nextCheckAt, "2 回目のあとは 12 時間後")
        // 3 回目でも、最初の失敗から 24 時間たっていなければ UNKNOWN のまま
        s = s.apply(transientFailure(), T0 + 20 * H, cfg, 0.0)
        assertEquals(LinkStatus.UNKNOWN, s.status)
        assertEquals(3, s.consecutiveFailures)
        assertEquals(T0 + 20 * H + 24 * H, s.nextCheckAt, "3 回目以降は 24 時間")
        // 24 時間たって、なお失敗なら BROKEN
        s = s.apply(transientFailure(), T0 + 1 * H + 24 * H, cfg, 0.0)
        assertEquals(LinkStatus.BROKEN, s.status)
        assertEquals(LinkReason.TIMEOUT, s.reason)
        assertEquals(T0 + 1 * H, s.firstFailedAt)
    }

    @Test
    fun threeQuickFailuresWithinADayStayUnknown() {
        var s = LinkState.pending(T0)
        for (i in 0 until 6) s = s.apply(transientFailure(), T0 + i * H, cfg, 0.0)
        assertEquals(LinkStatus.UNKNOWN, s.status)
        assertEquals(6, s.consecutiveFailures)
    }

    @Test
    fun aSuccessInTheMiddleResetsTheCount() {
        var s = LinkState.pending(T0).apply(transientFailure(), T0, cfg, 0.0).apply(transientFailure(), T0 + 6 * H, cfg, 0.0)
        s = s.apply(ok(), T0 + 20 * H, cfg, 0.0)
        s = s.apply(transientFailure(), T0 + 40 * H, cfg, 0.0)
        assertEquals(LinkStatus.UNKNOWN, s.status)
        assertEquals(1, s.consecutiveFailures)
        assertEquals(T0 + 40 * H, s.firstFailedAt)
    }

    @Test
    fun accessDeniedAndRateLimitDoNotCountAsFailures() {
        var s = LinkState.pending(T0).apply(ok(), T0, cfg, 0.0)
        s = s.apply(LinkProbe.Result.unknown(LinkReason.ACCESS_DENIED, 403, false), T0 + D, cfg, 0.0)
        assertEquals(LinkStatus.UNKNOWN, s.status)
        assertEquals(LinkReason.ACCESS_DENIED, s.reason)
        assertEquals(0, s.consecutiveFailures)
        assertEquals(T0, s.lastOkAt)
        assertTrue(s.needsAttention(), "アクセス拒否は、要確認に出す")
        val limited = LinkState.pending(T0).apply(LinkProbe.Result(LinkProbe.Verdict.UNKNOWN, LinkReason.RATE_LIMITED, 429, false, 120_000), T0, cfg, 0.0)
        assertEquals(T0 + 120_000, limited.nextCheckAt)
        assertFalse(limited.needsAttention())
        val unsafe = LinkState.pending(T0).apply(LinkProbe.Result.unknown(LinkReason.REDIRECT_UNSAFE, 302, false), T0, cfg, 0.0)
        assertFalse(unsafe.needsAttention(), "転送先が未検査は、公開の地図に出さない (内部の名前の探索に使われないように)")
    }

    @Test
    fun needsAttentionOnlyAfterRepeatedFailures() {
        var s = LinkState.pending(T0).apply(transientFailure(), T0, cfg, 0.0)
        assertFalse(s.needsAttention(), "1 回きりの失敗は、地図に出さない")
        s = s.apply(transientFailure(), T0 + 6 * H, cfg, 0.0)
        assertTrue(s.needsAttention())
        assertFalse(LinkState.pending(T0).apply(broken(LinkReason.HTTP_404), T0, cfg, 0.0).needsAttention(), "BROKEN は要確認ではなく、リンク切れ")
        assertFalse(LinkState.pending(T0).apply(ok(), T0, cfg, 0.0).needsAttention())
    }

    @Test
    fun brokenStaysBrokenWhenTheRecheckIsInconclusive() {
        var s = LinkState.pending(T0).apply(broken(LinkReason.HTTP_404), T0, cfg, 0.0)
        s = s.apply(transientFailure(), T0 + D, cfg, 0.0)
        assertEquals(LinkStatus.BROKEN, s.status)
        assertEquals(LinkReason.HTTP_404, s.reason)
        assertEquals(T0, s.firstFailedAt)
        s = s.apply(LinkProbe.Result.unknown(LinkReason.ACCESS_DENIED, 403, false), T0 + 2 * D, cfg, 0.0)
        assertEquals(LinkStatus.BROKEN, s.status)
    }

    @Test
    fun deferChangesOnlyTheNextCheckTime() {
        val before = LinkState.pending(T0).apply(ok(), T0, cfg, 0.0)
        val after = before.apply(LinkProbe.Result.defer(3_600_000L), T0 + D, cfg, 0.0)
        assertEquals(LinkStatus.OK, after.status)
        assertEquals(before.lastCheckedAt, after.lastCheckedAt)
        assertEquals(T0 + D + 3_600_000L, after.nextCheckAt)
    }

    @Test
    fun thresholdsComeFromTheSettings() {
        cfg.brokenAfterFailures = 2
        cfg.brokenAfterHours = 1
        var s = LinkState.pending(T0).apply(transientFailure(), T0, cfg, 0.0)
        s = s.apply(transientFailure(), T0 + 2 * H, cfg, 0.0)
        assertEquals(LinkStatus.BROKEN, s.status)
    }

    companion object {
        private const val H = 3_600_000L
        private const val D = 24 * H
        private const val T0 = 1_000 * D

        private fun ok(): LinkProbe.Result {
            return LinkProbe.Result.ok(200)
        }

        private fun broken(r: LinkReason): LinkProbe.Result {
            return LinkProbe.Result.broken(r, 404)
        }

        private fun transientFailure(): LinkProbe.Result {
            return LinkProbe.Result.unknown(LinkReason.TIMEOUT, 0, true)
        }
    }
}
