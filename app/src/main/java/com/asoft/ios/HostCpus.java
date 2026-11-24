package com.asoft.ios;

/**
 * The CPUs Android lets this process run on, from /proc/self/status's Cpus_allowed_list. Part of the setup's
 * tick line (PhoneSetup26.hostText): Android confines an app to the little cores when it is not on top
 * (2026-11-16: a DataMigration boot on a Galaxy S22 Ultra logged "cpuset 0-3" and started iOS's UI in 1414 s,
 * where the same kind of boot on the S25 takes about 50 s). The list says which of the phone's cores the boot
 * had, so a slow report can be told from a stopped one.
 */
final class HostCpus {
    private HostCpus() {}

    /** "0-3", "0-7", "" when /proc/self/status cannot be read. */
    static String allowed() {
        return allowed("self");
    }

    /**
     * The same for process @pid ("self" for this one). QEMU is a child of the app (Render.spawn) and Android moves
     * the app's own process between its cpusets: QEMU's list is the one its vCPUs run on.
     */
    static String allowed(String pid) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/" + pid + "/status"))) {
            for (String l; (l = r.readLine()) != null; ) {
                if (l.startsWith("Cpus_allowed_list:")) {
                    return parse(l);
                }
            }
        } catch (java.io.IOException ignored) {
            // no line is no harm
        }
        return "";
    }

    /** The list of a "Cpus_allowed_list:\t0-3" line, or "" when it is not one. */
    static String parse(String line) {
        if (line == null || !line.startsWith("Cpus_allowed_list:")) {
            return "";
        }
        String v = line.substring(line.indexOf(':') + 1).trim();
        return v.matches("[0-9][0-9,\\- ]*") ? v.replace(" ", "") : "";
    }

    /** The log's words: "cpus 0-3", "cpus ?". */
    static String text() {
        String a = allowed();
        return a.isEmpty() ? "cpus ?" : "cpus " + a;
    }

    /** "cpus 0-1,4-5, qemu cpus 0-7": the app's, then QEMU's (@qemuPid; left out when it is not running or the same). */
    static String text(int qemuPid) {
        String q = qemuPid > 0 ? allowed(String.valueOf(qemuPid)) : "";
        return text() + (q.isEmpty() || q.equals(allowed()) ? "" : ", qemu cpus " + q);
    }
}
