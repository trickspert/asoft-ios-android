package com.asoft.ios;

import java.util.Locale;

/**
 * Decides when the iOS 26 second boot's DataMigration wait (PhoneSetup26.iosWallpaper) has gone wrong, from what the
 * app sees without the guest's help: whether its shell answered, the new console text, the frames shown, the disk's
 * writes. See notes/handover-2026-11-13/k18-stall.md for the stall: the shell stops answering, launchd respawns bash
 * every ~10 s, the pid counter climbs past 25,000 (a healthy boot ends near 1,250). The limits count from its last
 * answer (SetupLimits), except for a churn that goes on while the shell still answers now and then (the respawned
 * bash is the console's own shell: a poll inside one bash's life is answered), which is measured by how long the pid
 * counter has been racing. A sign of life is a console line not printed before, a higher pid, a new frame, an
 * answer. No Android here.
 */
final class MigrationWatch {
    enum Verdict { WAIT, STALL_CHURN, STALL_CHURN_ANSWERING, STALL_DEAD, STALL_SILENT, CEILING }

    private final long start;
    private final ConsoleLife life = new ConsoleLife();
    private long lastAnswer, lastSign;
    private int pidAtAnswer, pidSeen, frames = -1;
    // the churn by time: the current bucket, and since when every bucket has been a churning one (-1: not churning)
    private long bucketStart, churnSince = -1;
    private int pidAtBucket;
    // the disk: the last total of bytes written (-1 unknown), and when it last grew by enough
    private long writes = -1, lastWrites = -1;

    MigrationWatch(long nowMs) {
        start = nowMs;
        lastAnswer = nowMs;
        lastSign = nowMs;
        bucketStart = nowMs;
    }

    /**
     * One look at the guest: @answered the shell answered this pass; @newText the console text since the last
     * look; @framesShown the screen's frame counter (-1 when unknown).
     */
    void observe(long nowMs, boolean answered, String newText, int framesShown) {
        observe(nowMs, answered, newText, framesShown, -1);
    }

    /** The same, with @bytesWritten the disks' total of bytes written so far (-1 when unknown). */
    void observe(long nowMs, boolean answered, String newText, int framesShown, long bytesWritten) {
        int novel = life.feed(newText);
        // a pid higher than any before is a program launchd has just started: the guest runs, even when every line
        // it prints is one it has printed before (the S25's churn of 2026-11-14 printed its last new line 13 min
        // in and came back after 26). Such a guest is ended by the churn limit, not as one that shows nothing.
        if (novel > 0 || life.maxPid() > pidSeen) {
            lastSign = nowMs;
        }
        pidSeen = life.maxPid();
        if (framesShown >= 0) {
            if (frames >= 0 && framesShown > frames) {
                lastSign = nowMs;
            }
            frames = framesShown;
        }
        if (answered) {
            lastAnswer = nowMs;
            lastSign = nowMs;
            pidAtAnswer = life.maxPid();
        }
        if (nowMs - bucketStart >= SetupLimits.CHURN_BUCKET_MS) {
            boolean churning = life.maxPid() >= SetupLimits.CHURN_PID && life.maxPid() - pidAtBucket >= SetupLimits.CHURN_BUCKET_RISE;
            if (!churning) {
                churnSince = -1;
            } else if (churnSince < 0) {
                churnSince = bucketStart;
            }
            bucketStart = nowMs;
            pidAtBucket = life.maxPid();
        }
        if (bytesWritten >= 0) {
            if (writes >= 0 && bytesWritten - writes >= SetupLimits.MIGRATION_WRITES_MIN_BYTES) {
                lastWrites = nowMs;
            }
            if (writes < 0 || bytesWritten - writes >= SetupLimits.MIGRATION_WRITES_MIN_BYTES || bytesWritten < writes) {
                writes = bytesWritten;
            }
        }
    }

    boolean racing() {
        return life.maxPid() >= SetupLimits.CHURN_PID && life.maxPid() - pidAtAnswer >= SetupLimits.CHURN_RISE;
    }

    /** Whether the pid counter has been racing for at least one whole bucket, answers or not. */
    boolean churning() {
        return churnSince >= 0;
    }

    /** Churning, and the shell has answered since it began: the churn only STALL_CHURN_ANSWERING ends. */
    boolean churningAnswering() {
        return churnSince >= 0 && lastAnswer > churnSince;
    }

    /** How long it has been racing without a break, in minutes (0 when it is not). */
    long churnMin(long nowMs) {
        return churnSince < 0 ? 0 : (nowMs - churnSince) / SetupLimits.MIN;
    }

    Verdict verdict(long nowMs) {
        long silent = nowMs - lastAnswer;
        if (silent >= SetupLimits.MIGRATION_CHURN_SILENT_MS && racing()) {
            return Verdict.STALL_CHURN;
        }
        // the churn that the silence rules cannot see: the shell answered during it (a churn with a silent shell is
        // the rule above's)
        if (churningAnswering() && nowMs - churnSince >= SetupLimits.MIGRATION_CHURN_ANSWERING_MS) {
            return Verdict.STALL_CHURN_ANSWERING;
        }
        if (nowMs - lastSign >= SetupLimits.MIGRATION_DEAD_SILENT_MS && silent >= SetupLimits.MIGRATION_DEAD_SILENT_MS) {
            return Verdict.STALL_DEAD;
        }
        if (silent >= SetupLimits.MIGRATION_SILENT_MS) {
            return Verdict.STALL_SILENT;
        }
        if (nowMs - start >= SetupLimits.MIGRATION_CEILING_MS && !stillWriting(nowMs)) {
            return Verdict.CEILING;
        }
        return Verdict.WAIT;
    }

    /** Past the ceiling, below the longer one: not churning and the disk written lately. */
    boolean stillWriting(long nowMs) {
        return nowMs - start < SetupLimits.MIGRATION_CEILING_MAX_MS && churnSince < 0 && lastWrites >= 0
                && nowMs - lastWrites < SetupLimits.MIGRATION_WRITES_RECENT_MS;
    }

    long silentMin(long nowMs) {
        return (nowMs - lastAnswer) / SetupLimits.MIN;
    }

    int maxPid() {
        return life.maxPid();
    }

    /** What the setup screen adds while the shell is quiet or the programs keep restarting; "" otherwise. */
    String quietStatus(long nowMs) {
        // (a churn with a silent shell is told by the silence below, and ended by the silent churn's rule)
        String churn = churnSince < 0 || lastAnswer <= churnSince ? "" : String.format(Locale.ROOT,
                " iOS's programs have been restarting over and over for %d min (a known problem; the setup stops it at %d).",
                churnMin(nowMs), SetupLimits.MIGRATION_CHURN_ANSWERING_MS / SetupLimits.MIN);
        long silent = silentMin(nowMs);
        if (silent < 1) {
            return churn;
        }
        long sign = (nowMs - lastSign) / SetupLimits.MIN;
        return String.format(Locale.ROOT, " iOS has not answered for %d min; last sign of life %s.", silent,
                sign < 1 ? "just now" : sign + " min ago") + churn;
    }

    /** What went wrong, in plain words, for the log and the error the user reads. */
    static String stallText(Verdict v, long silentMin) {
        return stallText(v, silentMin, 0);
    }

    /** The same; @churnMin for STALL_CHURN_ANSWERING. */
    static String stallText(Verdict v, long silentMin, long churnMin) {
        switch (v) {
            case STALL_CHURN:
                return "iOS stopped responding during its own setup: its programs have been restarting over and over for " + silentMin + " min.";
            case STALL_CHURN_ANSWERING:
                return "iOS is stuck in its own setup: its programs, its console's shell among them, have been restarting over and over for "
                        + churnMin + " min, so DataMigration is not getting anywhere in this boot.";
            case STALL_DEAD:
                return "iOS stopped responding during its own setup: nothing has changed on it for " + silentMin + " min.";
            case STALL_SILENT:
                return "iOS stopped responding during its own setup " + silentMin + " min ago.";
            default:
                return "";
        }
    }
}
