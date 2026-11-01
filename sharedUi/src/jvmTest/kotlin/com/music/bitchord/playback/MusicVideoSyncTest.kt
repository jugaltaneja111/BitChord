package com.music.bitchord.playback

import com.music.bitchord.playback.MusicVideoSync.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MusicVideoSyncTest {
    private fun step(
        target: Long = 10_000,
        video: Long = 10_000,
        playing: Boolean = true,
        inGap: Boolean = false,
        buffering: Boolean = false,
        now: Long = 100_000,
        lastSeek: Long = 0,
        lead: Long = 250,
        rate: Float = 1f,
    ) = MusicVideoSync.step(target, video, playing, inGap, buffering, now, lastSeek, lead, rate)

    @Test fun pausedCloseEnoughJustPauses() = assertEquals(Action.Pause, step(playing = false, video = 10_030))

    @Test fun pausedOffSnapsToTheExactFrame() =
        assertEquals(Action.Seek(10_000, play = false), step(playing = false, video = 10_100))

    @Test fun pausedRightAfterASeekWaitsForIt() =
        assertEquals(Action.Pause, step(playing = false, video = 10_100, now = 100_000, lastSeek = 99_950))

    @Test fun inASkippedGapThePictureHolds() = assertEquals(Action.Hold, step(inGap = true, video = 5_000))

    @Test fun underTheThresholdPlaysAtTheAudiosRate() = assertEquals(Action.Play(1.25f), step(video = 10_020, rate = 1.25f))

    @Test fun aheadSlowsDownBehindSpeedsUp() {
        val ahead = step(video = 10_100) as Action.Play
        val behind = step(video = 9_900) as Action.Play
        assertEquals(0.9f, ahead.rate, 1e-4f)
        assertEquals(1.1f, behind.rate, 1e-4f)
    }

    @Test fun theNudgeIsClampedToTenPercent() {
        val far = step(video = 10_400) as Action.Play
        assertEquals(0.9f, far.rate, 1e-4f)
    }

    @Test fun farOffSeeksAheadByTheLead() = assertEquals(Action.Seek(10_250, play = true), step(video = 12_000))

    @Test fun farOffInsideTheCooldownWaits() =
        assertEquals(Action.Play(1f), step(video = 12_000, now = 100_000, lastSeek = 99_000))

    @Test fun bufferingIsNotCorrected() = assertEquals(Action.Play(1f), step(video = 2_000, buffering = true))

    @Test fun theLeadAdaptsAndStaysInRange() {
        assertEquals(400, MusicVideoSync.nextSeekLead(250, 550))
        assertEquals(1_500, MusicVideoSync.nextSeekLead(1_500, 5_000))
        assertTrue(MusicVideoSync.nextSeekLead(0, -100) >= 0)
    }
}
