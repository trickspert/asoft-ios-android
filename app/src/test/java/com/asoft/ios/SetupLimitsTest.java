package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The wait limits of the phone's setup against sequences taken from the user reports of 2026-11-12/13
 * (notes/user-logs/open-2026-11-14: android-issue59, 23, 55, 65). Where a report holds only the end of the console,
 * the earlier part is built from what the same reports say (marked "modelled") and the real lines are the report's.
 */
public class SetupLimitsTest {
    private static final long MIN = SetupLimits.MIN;

    // ---- real console lines ----------------------------------------------------------------------------

    /** #59's console tail (Helio G99, after 2 h), verbatim. */
    private static final String ISSUE59_TAIL = String.join("\n",
            "SWEET AKS: homed sel 7 arg0 0 in 1/0 out 1/0 -> 0x0",
            "SWEET AKS: sharingd sel 35 arg0 0 in 1/0 out 0/4096 -> 0x0",
            "exec misd", "exec symptomsd", "exec xpcproxy",
            "SWEET AKS: tccd sel 0 arg0 0 in 0/0 out 0/0 -> 0x0",
            "SWEET AKS: tccd sel 7 arg0 0 in 1/0 out 1/0 -> 0x0",
            "exec fairplayd.H2",
            "SWEET AKS: securityd sel 10 arg0 0 in 2/32 out 1/40 -> 0xe00002c7",
            "exec xpcproxy", "exec fairplayd.H2",
            "SWEET AKS: securityd sel 10 arg0 0 in 2/32 out 1/40 -> 0xe00002c7",
            "exec xpcproxy", "exec cloudpaird",
            "SWEET AKS: lockdownd sel 0 arg0 0 in 0/0 out 0/0 -> 0x0",
            "SWEET AKS: lockdownd sel 7 arg0 0 in 1/0 out 1/0 -> 0x0",
            "exec bluetoothuserd", "exec pfd", "exec xpcproxy") + "\n";

    /** The repeating lines of #55's console tail (Xiaomi 15, minute 180). */
    private static final String ISSUE55_NOISE = String.join("\n",
            "hfs: set hardlink bit on vol=iOSRoot cnid=450710 inoid=561978490",
            "hfs: set hardlink bit on vol=iOSRoot cnid=450710 inoid=561978490",
            "\"AppleSEPKeyStore\":pid:0,:2979: SEP is not booted. AKS request is not processed",
            "\"AppleSEPKeyStore\":pid:0,:3085: sep_endpoint() failed to initialize",
            "\"AppleSEPKeyStore\":pid:0,:3209: no sks endpoint to send to",
            "\"AppleSEPKeyStore\":pid:0,sel:1:2979: SEP is not booted. AKS request is not processed",
            "\"AppleSEPKeyStore\":pid:0,sel:1:3085: sep_endpoint() failed to initialize",
            "\"AppleSEPKeyStore\":pid:0,sel:1:3209: no sks endpoint to send to") + "\n";

    /** A name made of letters only for second @s (digits would be ignored when lines are compared). */
    private static String uniq(long s) {
        StringBuilder b = new StringBuilder();
        for (long k = s / 30 + 1; k > 0; k /= 26) {
            b.append((char) ('a' + k % 26));
        }
        return b.toString();
    }

    private static String amfi(int pid) {
        return "AMFI: Launch Constraint Violation (not enforcing), error info: c[6]p[2]m[3]e[255], (Process was launched as a system"
                + " service unexpectedly but met System Service constraints) launching proc[vc: 1 pid: " + pid + "]: /bin/bash, launch type 1\n";
    }

    /** #65's console tail (S22 Ultra, minute 182): our own polls echoed, nothing from iOS. */
    private static final String ISSUE65_TAIL = String.join("\n",
            "bash-5.3# <m.apple.migration.plist; echo @@G606089186289G\"E\"@@",
            "@@G606089186289GS@@",
            "echo @@G606149307290G\"S\"@@; cat /var/mobile/Library/Preferences/com.apple.migration.plist; echo @@G606149307290G\"E\"@@",
            "echo @@G606209378291G\"S\"@@; cat /var/mobile/Library/Preferences/com.apple.migration.plist; echo @@G606209378291G\"E\"@@",
            "echo @@G606269509292G\"S\"@@; cat /var/mobile/Library/Preferences/com.apple.migration.plist; echo @@G606269509292G\"E\"@@") + "\n";

    // ---- the console reader ----------------------------------------------------------------------------

    @Test
    public void linesThatOnlyRepeatAreNotNew() {
        ConsoleLife l = new ConsoleLife();
        assertTrue(l.feed(ISSUE55_NOISE) > 0);
        assertEquals(0, l.feed(ISSUE55_NOISE));
        assertEquals(0, l.feed(ISSUE55_NOISE.replace("450710", "450708").replace("561978490", "769377837")));  // only the numbers differ
    }

    @Test
    public void theAppsOwnEchoesAndPromptsAreNotNew() {
        ConsoleLife l = new ConsoleLife();
        assertEquals(0, l.feed(ISSUE65_TAIL + "bash-5.3# \n\n"));
        assertEquals(1, l.feed("bash-5.3# exec newdaemon\n"));       // the guest's text behind a prompt counts
    }

    @Test
    public void aLineThatIsNotFinishedWaitsForItsEnd() {
        ConsoleLife l = new ConsoleLife();
        assertEquals(0, l.feed("exec mi"));
        assertEquals(1, l.feed("sd\n"));
    }

    @Test
    public void theHighestPidIsKeptAndTheEmptyPidZeroIsNoPid() {
        ConsoleLife l = new ConsoleLife();
        l.feed(ISSUE55_NOISE);
        assertEquals(0, l.maxPid());
        l.feed(amfi(24746) + amfi(25302) + amfi(895));
        assertEquals(25302, l.maxPid());
    }

    // ---- iOS 16 first start ----------------------------------------------------------------------------

    /** Modelled: new daemons keep appearing for 2 h, the real tail of #59 comes at minute 119. */
    private static void feed59(FirstStartWatch w, long startMs, int untilMin) {
        for (int m = 1; m <= untilMin; m++) {
            long now = startMs + m * MIN;
            String text = m == 119 ? ISSUE59_TAIL : "exec daemon" + m + "x\n";
            w.observe(now, text.replaceAll("daemon\\d+x", "daemon" + (char) ('a' + m % 26) + (char) ('a' + m / 26 % 26)), false);
            if (w.verdict(now) != FirstStartWatch.Verdict.WAIT) {
                throw new AssertionError("gave up at minute " + m + ": " + w.verdict(now));
            }
        }
    }

    @Test
    public void issue59KeepsWaitingPastTwoHoursWhileNewLinesCome() {
        FirstStartWatch w = new FirstStartWatch(0);
        feed59(w, 0, 120);
        assertEquals(FirstStartWatch.Verdict.WAIT, w.verdict(120 * MIN));          // the old limit
        assertTrue(w.status(120 * MIN).contains("Still working: last sign of life"));
        for (int m = 121; m <= 300; m++) {
            w.observe(m * MIN, "exec lateDaemon" + (char) ('a' + m % 26) + (char) ('a' + m / 26) + "\n", false);
            assertEquals(FirstStartWatch.Verdict.WAIT, w.verdict(m * MIN));
        }
    }

    @Test
    public void issue59WouldStopWhenTheNewLinesStopForAnHour() {
        FirstStartWatch w = new FirstStartWatch(0);
        feed59(w, 0, 120);
        // from minute 121 the console only repeats the lines of the tail
        for (int m = 121; m <= 179; m++) {
            w.observe(m * MIN, ISSUE59_TAIL, false);
        }
        assertEquals(FirstStartWatch.Verdict.WAIT, w.verdict(179 * MIN));          // the last new line came at minute 120
        w.observe(180 * MIN, ISSUE59_TAIL, false);
        assertEquals(FirstStartWatch.Verdict.QUIET, w.verdict(180 * MIN));
        assertTrue(w.giveUpText(FirstStartWatch.Verdict.QUIET, 180 * MIN).contains("Resume"));
    }

    @Test
    public void issue23PrintedNothingAndStopsAfterThirtyMinutes() {
        // android-issue23.json: serial.txt never existed, QEMU ended after 1 s; the old setup waited 2 h 16 min
        FirstStartWatch w = new FirstStartWatch(0);
        for (int m = 1; m < 30; m++) {
            w.observe(m * MIN, "", false);
            assertEquals(FirstStartWatch.Verdict.WAIT, w.verdict(m * MIN));
        }
        w.observe(30 * MIN, "", false);
        assertEquals(FirstStartWatch.Verdict.NO_CONSOLE, w.verdict(30 * MIN));
        assertTrue(w.giveUpText(FirstStartWatch.Verdict.NO_CONSOLE, 30 * MIN).contains("printed nothing"));
    }

    @Test
    public void aHealthyS25FirstStartIsNeverTouched() {
        // the S25: 23 min, new lines all along, the display goes off and on at the end
        FirstStartWatch w = new FirstStartWatch(0);
        for (int m = 1; m <= 23; m++) {
            w.observe(m * MIN, "exec d" + (char) ('a' + m) + "\n" + (m == 20 ? "SWEET FB: display off\n" : ""), m == 20);
            assertEquals(FirstStartWatch.Verdict.WAIT, w.verdict(m * MIN));
        }
        assertEquals(FirstStartWatch.Verdict.WAIT, w.verdict(24 * MIN));
    }

    @Test
    public void aMilestoneCountsAsLifeEvenWithoutANewLine() {
        FirstStartWatch w = new FirstStartWatch(0);
        w.observe(MIN, "exec a\n", false);
        w.observe(50 * MIN, "exec a\n", true);
        assertEquals(FirstStartWatch.Verdict.WAIT, w.verdict(109 * MIN));
        assertEquals(FirstStartWatch.Verdict.QUIET, w.verdict(110 * MIN));
    }

    @Test
    public void theFirstStartStopsAtTheCeilingEvenWithNewLines() {
        FirstStartWatch w = new FirstStartWatch(0);
        long end = SetupLimits.FIRST_START_CEILING_MS;
        w.observe(end - MIN, "exec lastnew\n", false);
        assertEquals(FirstStartWatch.Verdict.WAIT, w.verdict(end - MIN));
        w.observe(end, "exec reallylast\n", false);
        assertEquals(FirstStartWatch.Verdict.CEILING, w.verdict(end));
        assertTrue(SetupLimits.FIRST_START_CEILING_MS > 2 * 60 * MIN);
    }

    // ---- iOS 26 DataMigration --------------------------------------------------------------------------

    /** One pass of the wait loop is 30 s to 2 min; the sequences use 30 s. Returns the first minute with another verdict than WAIT, -1 if none. */
    private interface Pass {
        /** @return the console text of the pass starting at @sec (null = the shell answered, text "" ) */
        Obs at(long sec);
    }

    private static final class Obs {
        final boolean answered;
        final String text;
        final int frames;

        Obs(boolean answered, String text, int frames) {
            this.answered = answered;
            this.text = text;
            this.frames = frames;
        }
    }

    private static MigrationWatch.Verdict run(MigrationWatch w, long fromSec, long toSec, Pass pass, long[] stoppedAtSec) {
        for (long s = fromSec; s <= toSec; s += 30) {
            Obs o = pass.at(s);
            long now = s * 1000;
            w.observe(now, o.answered, o.text, o.frames);
            MigrationWatch.Verdict v = w.verdict(now);
            if (v != MigrationWatch.Verdict.WAIT) {
                stoppedAtSec[0] = s;
                return v;
            }
        }
        return MigrationWatch.Verdict.WAIT;
    }

    @Test
    public void issue55IsStoppedAfterTwoHoursOfChurn() {
        // Xiaomi 15: "DataMigration 60 min ... crash reports: (no answer)"; the shell is silent from about minute 55
        // (modelled: pid 25,302 at minute 180 means ~3.4 pids/s from minute 55; the repeating lines and the AMFI line are the report's)
        long onset = 55 * 60;
        Pass pass = s -> {
            if (s < onset) {
                return new Obs(true, "", 0);
            }
            int pid = (int) (895 + (s - onset) * 3.4);
            return new Obs(false, ISSUE55_NOISE + amfi(pid), 1000);
        };
        MigrationWatch w = new MigrationWatch(0);
        long[] at = new long[1];
        MigrationWatch.Verdict v = run(w, 0, 180 * 60, pass, at);
        assertEquals(MigrationWatch.Verdict.STALL_CHURN, v);
        long min = at[0] / 60;
        assertTrue("stopped at " + min, min >= 55 + 119 && min < 180);        // from the last answer, at 54.5 min
        assertTrue(w.maxPid() >= SetupLimits.CHURN_PID);
        assertTrue(MigrationWatch.stallText(v, 120).contains("stopped responding during its own setup"));
    }

    @Test
    public void theS25BootThatCameOutOfTheChurnIsNotStopped() {
        // S25, 2026-11-14: the shell answers until minute 20, then 26 min of silence while launchd respawns bash and
        // the pid counter goes from 468 past 13,850; DataMigration is done at minute 45 and the shell answers again
        long onset = 20 * 60, back = 46 * 60;
        Pass pass = s -> {
            if (s < onset || s >= back) {
                return new Obs(true, "", 1320);
            }
            return new Obs(false, ISSUE55_NOISE + amfi((int) (468 + (s - onset) * 8.6)), 1320);
        };
        MigrationWatch w = new MigrationWatch(0);
        long[] at = new long[1];
        assertEquals(MigrationWatch.Verdict.WAIT, run(w, 0, 45 * 60, pass, at));
        assertTrue(w.racing());                 // the fingerprint is there...
        assertTrue(w.maxPid() > 13_000);
        assertEquals(MigrationWatch.Verdict.WAIT, run(w, 45 * 60 + 30, 60 * 60, pass, at));      // ...and the boot is left alone
        assertFalse(w.racing());                // an answer ends it
        assertTrue(SetupLimits.MIGRATION_CHURN_SILENT_MS >= 120 * MIN);
        assertTrue(SetupLimits.MIGRATION_CHURN_SILENT_MS >= SetupLimits.MIGRATION_SILENT_MS);
    }

    @Test
    public void aChurnIsNotEndedAsAGuestThatShowsNothing() {
        // the S25's console in the churn: its last line not printed before came 13 min in, the pids went on rising;
        // an hour of that is not the "nothing has changed" stall
        long onset = 20 * 60;
        Pass pass = s -> s < onset ? new Obs(true, "", 1320)
                : new Obs(false, ISSUE55_NOISE + amfi((int) (468 + (s - onset) * 8.6)), 1320);
        MigrationWatch w = new MigrationWatch(0);
        long[] at = new long[1];
        assertEquals(MigrationWatch.Verdict.WAIT, run(w, 0, 20 * 60 + 110 * 60, pass, at));
        assertEquals(" iOS has not answered for 110 min; last sign of life just now.", w.quietStatus((20 + 110) * 60_000L - 30_000L));
        assertEquals(MigrationWatch.Verdict.STALL_CHURN, run(w, 130 * 60 + 30, 150 * 60, pass, at));
    }

    @Test
    public void issue55EvenWithoutThePidsItIsStoppedBySilenceAndStillConsole() {
        // the same silence read from a phone whose console tail had no AMFI line: nothing new for an hour
        long onset = 55 * 60;
        Pass pass = s -> s < onset ? new Obs(true, "", 0) : new Obs(false, ISSUE55_NOISE, 1000);
        long[] at = new long[1];
        MigrationWatch.Verdict v = run(new MigrationWatch(0), 0, 180 * 60, pass, at);
        assertEquals(MigrationWatch.Verdict.STALL_DEAD, v);
        assertTrue(at[0] / 60 <= 55 + 61 + 1);
    }

    @Test
    public void issue65IsStoppedWellBefore3Hours() {
        // S22 Ultra: 81 % at minute 57 (the last app-log line), then only our own polls echoed back in the console
        Pass pass = s -> s <= 57 * 60 ? new Obs(true, "", 12) : new Obs(false, ISSUE65_TAIL, 12);
        MigrationWatch w = new MigrationWatch(0);
        long[] at = new long[1];
        MigrationWatch.Verdict v = run(w, 0, 182 * 60, pass, at);
        assertEquals(MigrationWatch.Verdict.STALL_DEAD, v);
        assertTrue("stopped at " + at[0] / 60, at[0] / 60 <= 57 + 61);
        assertTrue(at[0] / 60 < 150);
        // the status line the user sees while it waits
        MigrationWatch w2 = new MigrationWatch(0);
        w2.observe(57 * 60_000L, true, "", 12);
        w2.observe(87 * 60_000L, false, ISSUE65_TAIL, 12);
        assertEquals(" iOS has not answered for 30 min; last sign of life 30 min ago.", w2.quietStatus(87 * 60_000L));
    }

    @Test
    public void aHealthySlowS25LikeRunIsNeverTouched() {
        // the S25: the shell answers on every pass for 62 min, the pid counter ends near 1,250, a few new lines come
        Pass pass = s -> new Obs(true, s % 600 == 0 ? "exec migr" + uniq(s) + "\n" + amfi((int) (46 + s / 3)) : "", (int) (s / 60));
        MigrationWatch w = new MigrationWatch(0);
        long[] at = new long[1];
        assertEquals(MigrationWatch.Verdict.WAIT, run(w, 0, 62 * 60, pass, at));
        assertFalse(w.racing());
        assertEquals("", w.quietStatus(62 * 60_000L));
    }

    @Test
    public void aSlowPhoneWhoseShellKeepsAnsweringIsAllowedHoursAndStopsAtTheCeiling() {
        Pass pass = s -> new Obs(true, "", 5);
        MigrationWatch w = new MigrationWatch(0);
        long[] at = new long[1];
        MigrationWatch.Verdict v = run(w, 0, 6 * 3600, pass, at);
        assertEquals(MigrationWatch.Verdict.CEILING, v);
        assertEquals(SetupLimits.MIGRATION_CEILING_MS / 1000, at[0]);
        assertTrue(SetupLimits.MIGRATION_CEILING_MS > 3 * 60 * MIN);       // the old limit was 3 h
    }

    /** run() with the disks' bytes written so far at second @s from @written (-1: unknown). */
    private static MigrationWatch.Verdict runW(MigrationWatch w, long fromSec, long toSec, Pass pass,
            java.util.function.LongUnaryOperator written, long[] stoppedAtSec) {
        for (long s = fromSec; s <= toSec; s += 30) {
            Obs o = pass.at(s);
            long now = s * 1000;
            w.observe(now, o.answered, o.text, o.frames, written.applyAsLong(s));
            MigrationWatch.Verdict v = w.verdict(now);
            if (v != MigrationWatch.Verdict.WAIT) {
                stoppedAtSec[0] = s;
                return v;
            }
        }
        return MigrationWatch.Verdict.WAIT;
    }

    /**
     * SM-S938U, 2026-11-24: from about minute 115 (modelled from pid 61,750 at minute 270 and 72,634 at 296, ~400 pids a
     * minute) the console's own bash is respawned every ~10 s; a poll inside one bash's life is answered (the shell was
     * "silent 4 min" at most ticks, never 15), so only the 5 h ceiling ended it, as "a slow phone".
     */
    private static final Pass S938U = s -> {
        long onset = 115 * 60;
        if (s < onset) {
            return new Obs(true, "", 1320);
        }
        int pid = (int) (1250 + (s - onset) * 400 / 60);
        boolean answered = (s / 30) % 8 == 0;
        return new Obs(answered, ISSUE55_NOISE + amfi(pid) + "bash-5.3# \n", 1320);
    };

    @Test
    public void aChurnWhoseShellAnswersNowAndThenIsStoppedAfterTwoHoursOfIt() {
        MigrationWatch w = new MigrationWatch(0);
        long[] at = new long[1];
        MigrationWatch.Verdict v = run(w, 0, 6 * 3600, S938U, at);
        assertEquals(MigrationWatch.Verdict.STALL_CHURN_ANSWERING, v);
        long min = at[0] / 60;
        // the first churning bucket starts at most a bucket after the onset; then 120 min of it
        assertTrue("stopped at " + min, min >= 115 + 120 && min <= 115 + 120 + 2 * SetupLimits.CHURN_BUCKET_MS / MIN);
        assertTrue(min * MIN < SetupLimits.MIGRATION_CEILING_MS);
        assertTrue(w.silentMin(at[0] * 1000) < 15);
        String text = MigrationWatch.stallText(v, w.silentMin(at[0] * 1000), w.churnMin(at[0] * 1000));
        assertTrue(text, text.contains("restarting over and over for " + w.churnMin(at[0] * 1000) + " min"));
        assertTrue(w.churnMin(at[0] * 1000) >= 120);
    }

    @Test
    public void theSetupScreenSaysSoWhileAnAnsweringChurnGoesOn() {
        MigrationWatch w = new MigrationWatch(0);
        long[] at = new long[1];
        assertEquals(MigrationWatch.Verdict.WAIT, run(w, 0, 180 * 60, S938U, at));
        assertTrue(w.churningAnswering());
        String status = w.quietStatus(180 * 60_000L);
        assertTrue(status, status.contains("restarting over and over"));
        assertFalse(new MigrationWatch(0).churningAnswering());
    }

    @Test
    public void aShortChurnWithAnAnsweringShellIsLeftAlone() {
        // like the S25's of 2026-11-14 (26 min, the counter past 13,850), but the shell answered now and then in it
        long onset = 20 * 60, back = 46 * 60;
        Pass pass = s -> {
            if (s < onset || s >= back) {
                return new Obs(true, "", 1320);
            }
            return new Obs((s / 30) % 8 == 0, ISSUE55_NOISE + amfi((int) (468 + (s - onset) * 8.6)), 1320);
        };
        MigrationWatch w = new MigrationWatch(0);
        long[] at = new long[1];
        assertEquals(MigrationWatch.Verdict.WAIT, run(w, 0, 4 * 3600, pass, at));
        assertFalse(w.churning());
    }

    @Test
    public void aSlowPhoneStillWritingItsDiskIsLetPastTheCeilingUntilTheWritesStop() {
        // the shell answers, no churn; DataMigration writes about 1 MB/s until minute 340
        Pass pass = s -> new Obs(true, "", 5);
        java.util.function.LongUnaryOperator writes = s -> Math.min(s, 340 * 60) * (1L << 20);
        MigrationWatch w = new MigrationWatch(0);
        long[] at = new long[1];
        assertEquals(MigrationWatch.Verdict.CEILING, runW(w, 0, 9 * 3600, pass, writes, at));
        assertTrue("stopped at " + at[0] / 60, at[0] / 60 >= 340 + 29 && at[0] / 60 <= 340 + 31);
        // writing all along: stopped at the longer ceiling
        MigrationWatch w2 = new MigrationWatch(0);
        assertEquals(MigrationWatch.Verdict.CEILING, runW(w2, 0, 9 * 3600, pass, s -> s * (1L << 20), at));
        assertEquals(SetupLimits.MIGRATION_CEILING_MAX_MS / 1000, at[0]);
        // a few log lines are no writes: the 5 h ceiling
        MigrationWatch w3 = new MigrationWatch(0);
        assertEquals(MigrationWatch.Verdict.CEILING, runW(w3, 0, 9 * 3600, pass, s -> s * 64, at));
        assertEquals(SetupLimits.MIGRATION_CEILING_MS / 1000, at[0]);
    }

    @Test
    public void aChurningBootIsNotLetPastTheCeilingForItsWrites() {
        // the churn starts 10 min before the ceiling, too late for its own limit; its log writes do not extend the wait
        long onset = (SetupLimits.MIGRATION_CEILING_MS / 1000) - 600;
        Pass pass = s -> s < onset ? new Obs(true, "", 5)
                : new Obs((s / 30) % 8 == 0, amfi((int) (1250 + (s - onset) * 400 / 60)), 5);
        MigrationWatch w = new MigrationWatch(0);
        long[] at = new long[1];
        assertEquals(MigrationWatch.Verdict.CEILING, runW(w, 0, 9 * 3600, pass, s -> s * (1L << 20), at));
        assertTrue(at[0] * 1000 < SetupLimits.MIGRATION_CEILING_MS + 2 * SetupLimits.CHURN_BUCKET_MS);
    }

    @Test
    public void aShellThatComesBackIsNotAStall() {
        // #19: no answer at minute 55, read as done at 68.8; and 9 min of silence before a sync
        Pass pass = s -> (s >= 55 * 60 && s < 68 * 60) ? new Obs(false, "", 3) : new Obs(true, "", 3);
        long[] at = new long[1];
        assertEquals(MigrationWatch.Verdict.WAIT, run(new MigrationWatch(0), 0, 80 * 60, pass, at));
    }

    @Test
    public void aSilentShellWithNewConsoleLinesIsGivenTimeUpToTwoHours() {
        // K18 fact 9: one phone's shell was silent for more than 69 min and then came back; new console lines
        // all along mean the guest is doing something, so only the 120 min of silence ends the wait
        Pass pass = s -> {
            if (s < 50 * 60 || s >= 119 * 60) {
                return new Obs(true, "", 8);
            }
            return new Obs(false, s % 300 == 0 ? "exec other" + uniq(s) + "\n" : "", 8);
        };
        long[] at = new long[1];
        MigrationWatch w = new MigrationWatch(0);
        assertEquals(MigrationWatch.Verdict.WAIT, run(w, 0, 130 * 60, pass, at));
        Pass longer = s -> s < 50 * 60 ? new Obs(true, "", 8) : new Obs(false, s % 300 == 0 ? "exec other" + uniq(s) + "\n" : "", 8);
        MigrationWatch w2 = new MigrationWatch(0);
        assertEquals(MigrationWatch.Verdict.STALL_SILENT, run(w2, 0, 200 * 60, longer, at));
        assertEquals(50 * 60 - 30 + SetupLimits.MIGRATION_SILENT_MS / 1000, at[0]);   // from the last answer, at 49.5 min
    }

    @Test
    public void aMovingScreenIsASignOfLife() {
        Pass pass = s -> s < 50 * 60 ? new Obs(true, "", 8) : new Obs(false, ISSUE55_NOISE, 8 + (int) ((s - 50 * 60) / 120));
        long[] at = new long[1];
        MigrationWatch w = new MigrationWatch(0);
        assertEquals(MigrationWatch.Verdict.STALL_SILENT, run(w, 0, 200 * 60, pass, at));
        assertEquals(50 * 60 - 30 + SetupLimits.MIGRATION_SILENT_MS / 1000, at[0]);   // from the last answer, at 49.5 min
    }

    // ---- counting stalls -------------------------------------------------------------------------------

    @Test
    public void theThirdIdenticalStallAsksForAReport() {
        StallHistory h = new StallHistory();
        assertEquals(1, h.record("wallpaper", "churn"));
        h = StallHistory.parse(h.text());
        assertEquals(2, h.record("wallpaper", "churn"));
        h = StallHistory.parse(h.text());
        int n = h.record("wallpaper", "churn");
        assertEquals(3, n);
        String what = MigrationWatch.stallText(MigrationWatch.Verdict.STALL_CHURN, 40);
        String first = StallHistory.userText(what, 1);
        assertTrue(first.contains("Resume"));
        assertFalse(first.contains("report"));
        String third = StallHistory.userText(what, n);
        assertTrue(third.contains("third time in a row"));
        assertTrue(third.contains("known problem"));
        assertTrue(third.contains("a report helps"));
    }

    @Test
    public void aDifferentStallStartsTheCountAgainAndSuccessClearsIt() {
        StallHistory h = new StallHistory();
        h.record("wallpaper", "churn");
        h.record("wallpaper", "churn");
        assertEquals(1, h.record("wallpaper", "dead"));
        assertEquals(0, h.count("wallpaper", "churn"));
        h.clear("wallpaper");
        assertEquals("", h.text());
        assertEquals(0, StallHistory.parse("junk\nwallpaper:churn=x\n").count("wallpaper", "churn"));
    }
}
