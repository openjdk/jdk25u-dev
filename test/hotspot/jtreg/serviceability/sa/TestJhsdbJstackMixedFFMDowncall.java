/*
 * Copyright Amazon.com Inc. or its affiliates. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import java.util.List;

import jdk.test.lib.JDKToolLauncher;
import jdk.test.lib.SA.SATestUtils;
import jdk.test.lib.Utils;
import jdk.test.lib.apps.LingeredApp;
import jdk.test.lib.process.OutputAnalyzer;

/**
 * @test
 * @bug 8392990
 * @summary The mixed stack of a thread in a void FFM downcall must reach the
 *          downcall stub and the Java caller below the native frame.
 * @requires vm.hasSA
 * @requires os.family == "linux" & os.arch == "amd64"
 * @library /test/lib
 * @run driver TestJhsdbJstackMixedFFMDowncall
 */
public class TestJhsdbJstackMixedFFMDowncall {

    private static final String NATIVE_FRAME = "block_in_native";
    private static final String STUB_FRAME = "<nep_invoker_blob>";
    private static final String JAVA_FRAME = "LingeredAppWithFFMDowncall.blockInNative";

    // The lines PStack printed for the downcall thread: from its header line
    // to the next thread's header.
    private static List<String> downcallThreadSection(String stdout) {
        List<String> lines = stdout.lines().toList();
        int name = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("\"" + LingeredAppWithFFMDowncall.THREAD_NAME + "\"")) {
                name = i;
                break;
            }
        }
        if (name < 0) {
            throw new RuntimeException("No section for thread " + LingeredAppWithFFMDowncall.THREAD_NAME);
        }
        int end = name + 1;
        while (end < lines.size() && !lines.get(end).startsWith("----------------- ")) {
            end++;
        }
        return lines.subList(name, end);
    }

    private static int indexOf(List<String> section, String text) {
        for (int i = 0; i < section.size(); i++) {
            if (section.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean runJstack(LingeredApp app) throws Exception {
        JDKToolLauncher launcher = JDKToolLauncher.createUsingTestJDK("jhsdb");
        launcher.addVMArgs(Utils.getTestJavaOpts());
        launcher.addToolArg("jstack");
        launcher.addToolArg("--mixed");
        launcher.addToolArg("--pid");
        launcher.addToolArg(Long.toString(app.getPid()));

        ProcessBuilder pb = SATestUtils.createProcessBuilder(launcher);
        Process jhsdb = pb.start();
        OutputAnalyzer out = new OutputAnalyzer(jhsdb);
        jhsdb.waitFor();

        System.out.println(out.getStdout());
        System.err.println(out.getStderr());
        out.shouldHaveExitValue(0);

        List<String> section = downcallThreadSection(out.getStdout());
        System.out.println("Downcall thread section:");
        section.forEach(System.out::println);

        int nativeAt = indexOf(section, NATIVE_FRAME);
        if (nativeAt < 0) {
            // The thread is not yet (or no longer) in the native function; try again.
            return false;
        }
        int stubAt = indexOf(section, STUB_FRAME);
        int javaAt = indexOf(section, JAVA_FRAME);
        if (stubAt <= nativeAt || javaAt <= stubAt) {
            throw new RuntimeException("The mixed stack ends below " + NATIVE_FRAME
                    + ": expected " + STUB_FRAME + " and then " + JAVA_FRAME
                    + " after it (frame lines " + nativeAt + ", " + stubAt + ", " + javaAt + ")");
        }
        return true;
    }

    public static void main(String... args) throws Exception {
        SATestUtils.skipIfCannotAttach(); // throws SkippedException if attach not expected to work.
        LingeredApp app = null;
        try {
            // Needed for LingeredAppWithFFMDowncall to resolve its native library.
            String libPath = System.getProperty("java.library.path");
            String[] vmArgs = (libPath != null)
                ? Utils.prependTestJavaOpts("-Djava.library.path=" + libPath,
                                            "--enable-native-access=ALL-UNNAMED")
                : Utils.prependTestJavaOpts("--enable-native-access=ALL-UNNAMED");

            app = new LingeredAppWithFFMDowncall();
            LingeredApp.startAppExactJvmOpts(app, vmArgs);
            System.out.println("Started LingeredAppWithFFMDowncall with pid " + app.getPid());
            for (int attempt = 1; ; attempt++) {
                if (runJstack(app)) {
                    break;
                }
                if (attempt == 10) {
                    throw new RuntimeException(LingeredAppWithFFMDowncall.THREAD_NAME
                            + " never showed " + NATIVE_FRAME + " in 10 attempts");
                }
                Thread.sleep(1000);
            }
            System.out.println("Test Completed");
        } finally {
            LingeredApp.stopApp(app);
        }
    }
}
