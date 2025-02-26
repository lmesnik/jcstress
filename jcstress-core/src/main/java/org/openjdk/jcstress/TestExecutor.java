/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package org.openjdk.jcstress;

import org.openjdk.jcstress.infra.Status;
import org.openjdk.jcstress.infra.collectors.TestResult;
import org.openjdk.jcstress.infra.collectors.TestResultCollector;
import org.openjdk.jcstress.infra.processors.JCStressTestProcessor;
import org.openjdk.jcstress.infra.runners.TestConfig;
import org.openjdk.jcstress.infra.runners.WorkerSync;
import org.openjdk.jcstress.link.BinaryLinkServer;
import org.openjdk.jcstress.link.ServerListener;
import org.openjdk.jcstress.vm.CPULayout;
import org.openjdk.jcstress.vm.CompileMode;
import org.openjdk.jcstress.vm.OSSupport;
import org.openjdk.jcstress.util.StringUtils;
import org.openjdk.jcstress.vm.VMSupport;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages test execution for the entire run.
 * <p>
 * This executor is deliberately single-threaded for two reasons:
 * a) Tests are heavily multithreaded and spawning new threads here may
 * deplete the thread budget sooner rather than later;
 * b) Dead-locks in scheduling logic are more visible without threads;
 */
public class TestExecutor {

    private static final int SPIN_WAIT_DELAY_MS = 100;

    static final AtomicInteger ID = new AtomicInteger();

    private final BinaryLinkServer server;
    private final int maxThreads;
    private final Verbosity verbosity;
    private final TestResultCollector sink;
    private final EmbeddedExecutor embeddedExecutor;
    private final CPULayout cpuLayout;

    private final Map<String, VM> vmByToken;

    public TestExecutor(int maxThreads, Verbosity verbosity, TestResultCollector sink, boolean possiblyForked) throws IOException {
        this.maxThreads = maxThreads;
        this.verbosity = verbosity;
        this.sink = sink;
        this.vmByToken = new ConcurrentHashMap<>();

        cpuLayout = new CPULayout(maxThreads);

        server = possiblyForked ? new BinaryLinkServer(new ServerListener() {
            @Override
            public TestConfig onJobRequest(String token) {
                return vmByToken.get(token).jobRequest();
            }

            @Override
            public void onResult(String token, TestResult result) {
                vmByToken.get(token).recordResult(result);
            }
        }) : null;
        embeddedExecutor = new EmbeddedExecutor(sink, cpuLayout);
    }

    public void runAll(List<TestConfig> configs) {
        for (TestConfig cfg : configs) {
            List<Integer> acquiredCPUs = acquireCPUs(cfg.threads);

            switch (cfg.runMode) {
                case EMBEDDED:
                    embeddedExecutor.submit(cfg, acquiredCPUs);
                    break;
                case FORKED:
                    String token = "fork-token-" + ID.incrementAndGet();
                    VM vm = new VM(server.getHost(), server.getPort(), token, cfg, acquiredCPUs);
                    vmByToken.put(token, vm);
                    vm.start();
                    break;
                default:
                    throw new IllegalStateException("Unknown mode: " + cfg.runMode);
            }
        }

        // Wait until all threads are done, which means everything got processed
        acquireCPUs(maxThreads);

        server.terminate();
    }

    private List<Integer> acquireCPUs(int cpus) {
        List<Integer> acquired;
        while ((acquired = cpuLayout.tryAcquire(cpus)) == null) {
            processReadyVMs();
            try {
                Thread.sleep(SPIN_WAIT_DELAY_MS);
            } catch (InterruptedException e) {
                // do nothing
            }
        }

        return acquired;
    }

    private void processReadyVMs() {
        for (VM vm : vmByToken.values()) {
            if (vm.checkCompleted(sink)) {
                vmByToken.remove(vm.token, vm);
                cpuLayout.release(vm.claimedCPUs);
            }
        }
    }

    private class VM {
        private final String host;
        private final int port;
        private final String token;
        private final File stdout;
        private final File stderr;
        private final File compilerDirectives;
        private final TestConfig task;
        private final List<Integer> claimedCPUs;
        private Process process;
        private boolean processed;
        private IOException pendingException;
        private TestResult result;

        public VM(String host, int port, String token, TestConfig task, List<Integer> claimedCPUs) {
            this.host = host;
            this.port = port;
            this.token = token;
            this.claimedCPUs = claimedCPUs;
            this.task = task;
            try {
                this.stdout = File.createTempFile("jcstress", "stdout");
                this.stderr = File.createTempFile("jcstress", "stderr");
                this.compilerDirectives = File.createTempFile("jcstress", "directives");

                if (VMSupport.compilerDirectivesAvailable()) {
                    generateDirectives();
                }

                // Register these files for removal in case we terminate through the uncommon path
                this.stdout.deleteOnExit();
                this.stderr.deleteOnExit();
                this.compilerDirectives.deleteOnExit();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        void generateDirectives() throws IOException {
            PrintWriter pw = new PrintWriter(compilerDirectives);
            pw.println("[");

            // The task loop:
            pw.println("  {");
            pw.println("    match: \"" + task.generatedRunnerName + "::" + JCStressTestProcessor.TASK_LOOP_PREFIX + "*\",");

            // Avoid inlining the run loop, it should be compiled as separate hot code
            pw.println("    inline: \"-" + task.generatedRunnerName + "::" + JCStressTestProcessor.RUN_LOOP_PREFIX + "*\",");

            // Force inline the auxiliary methods and classes in the run loop
            pw.println("    inline: \"+" + task.generatedRunnerName + "::" + JCStressTestProcessor.AUX_PREFIX + "*\",");
            pw.println("    inline: \"+" + WorkerSync.class.getName() + "::*\",");
            pw.println("    inline: \"+java.util.concurrent.atomic.*::*\",");

            // The test is running in resource-constrained JVM. Block the task loop execution until
            // compiled code is available. This would allow compilers to work in relative peace.
            pw.println("    BackgroundCompilation: false,");

            pw.println("  },");

            // Force inline everything from WorkerSync. WorkerSync does not use anything
            // too deeply, so inlining everything is fine.
            pw.println("  {");
            pw.println("    match: \"" + WorkerSync.class.getName() + "::*" + "\",");
            pw.println("    inline: \"+*::*\",");

            // The test is running in resource-constrained JVM. Block the WorkerSync execution until
            // compiled code is available. This would allow compilers to work in relative peace.
            pw.println("    BackgroundCompilation: false,");

            pw.println("  },");

            // The run loops:
            CompileMode cm = task.getCompileMode();
            for (int a = 0; a < task.threads; a++) {
                String an = task.actorNames.get(a);

                pw.println("  {");
                pw.println("    match: \"" + task.generatedRunnerName + "::" + JCStressTestProcessor.RUN_LOOP_PREFIX + an + "\",");
                pw.println("    inline: \"+" + task.generatedRunnerName + "::" + JCStressTestProcessor.AUX_PREFIX + "*\",");

                // Force inline of actor methods if run in compiled mode: this would inherit
                // compiler for them. Forbid inlining of actor methods in interpreted mode:
                // this would make sure that while actor methods are running in interpreter,
                // the run loop still runs in compiled mode, running faster. The call to interpreted
                // method would happen anyway, even though through c2i transition.
                if (cm.isInt(a)) {
                    pw.println("    inline: \"-" + task.name + "::" + an + "\",");
                } else {
                    pw.println("    inline: \"+" + task.name + "::" + an + "\",");
                }

                // Run loop should be compiled with C2? Forbid C1 compilation then.
                if (cm.isC2(a)) {
                    pw.println("    c1: {");
                    pw.println("      Exclude: true,");
                    pw.println("    },");
                }

                // Run loop should be compiled with C1? Forbid C2 compilation then.
                if (cm.isC1(a)) {
                    pw.println("    c2: {");
                    pw.println("      Exclude: true,");
                    pw.println("    },");
                }

                if (VMSupport.printAssemblyAvailable() && verbosity.printAssembly() && !cm.isInt(a)) {
                    pw.println("    PrintAssembly: true,");
                }

                // The test is running in resource-constrained JVM. Block the run loop execution until
                // compiled code is available. This would allow compilers to work in relative peace.
                pw.println("    BackgroundCompilation: false,");

                pw.println("  },");
            }

            for (int a = 0; a < task.threads; a++) {
                String an = task.actorNames.get(a);

                // If this actor runs in interpreted mode, then actor method should not be compiled.
                // Allow run loop to be compiled with the best compiler available.
                if (cm.isInt(a)) {
                    pw.println("  {");
                    pw.println("    match: \"+" + task.name + "::" + an + "\",");
                    pw.println("    c1: {");
                    pw.println("      Exclude: true,");
                    pw.println("    },");
                    pw.println("    c2: {");
                    pw.println("      Exclude: true,");
                    pw.println("    },");
                    pw.println("  },");
                }
            }
            pw.println("]");
            pw.flush();
            pw.close();
        }

        void start() {
            try {
                List<String> command = new ArrayList<>();

                if (OSSupport.taskSetAvailable()) {
                    command.add("taskset");
                    command.add("-c");
                    command.add(StringUtils.join(claimedCPUs, ","));
                }

                // basic Java line
                command.addAll(VMSupport.getJavaInvokeLine());

                // jvm args
                command.addAll(task.jvmArgs);

                if (VMSupport.compilerDirectivesAvailable()) {
                    command.add("-XX:CompilerDirectivesFile=" + compilerDirectives.getAbsolutePath());
                }

                command.add(ForkedMain.class.getName());

                command.add(host);
                command.add(String.valueOf(port));

                // which config should the forked VM pull?
                command.add(token);

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectOutput(stdout);
                pb.redirectError(stderr);
                process = pb.start();
            } catch (IOException ex) {
                pendingException = ex;
            }
        }

        public synchronized TestConfig jobRequest() {
            if (processed) {
                return null;
            }
            processed = true;
            return getTask();
        }

        public TestConfig getTask() {
            return task;
        }

        public boolean checkCompleted(TestResultCollector sink) {
            // There is a pending exception that terminated the target VM.
            if (pendingException != null) {
                dumpFailure(sink, Collections.singleton(pendingException.getMessage()), Collections.emptyList());
                return true;
            }

            // Process is still alive, no need to ask about the status.
            if (process.isAlive()) {
                return false;
            }

            // Try to poll the exit code, and fail if it's not zero.
            try {
                int ecode = process.waitFor();

                List<String> out = new ArrayList<>();
                try {
                    out.addAll(Files.readAllLines(stdout.toPath()));
                } catch (IOException e) {
                    out.add("Failed to read stdout: " + e.getMessage());
                }

                List<String> err = new ArrayList<>();
                try {
                    err.addAll(Files.readAllLines(stderr.toPath()));
                } catch (IOException e) {
                    err.add("Failed to read stderr: " + e.getMessage());
                }

                if (ecode != 0) {
                    dumpFailure(sink, out, err);
                } else {
                    result.addVMOuts(out);
                    result.addVMErrs(err);
                    sink.add(result);
                }
            } catch (InterruptedException ex) {
                dumpFailure(sink, Collections.singleton(ex.getMessage()), Collections.emptyList());
            } finally {
                // The process is definitely dead, remove the temporary files.
                stdout.delete();
                stderr.delete();
            }
            return true;
        }

        private void dumpFailure(TestResultCollector sink, Collection<String> out, Collection<String> err) {
            TestConfig task = getTask();
            TestResult result = new TestResult(task, Status.VM_ERROR);
            for (String i : out) {
                result.addMessage(i);
            }
            for (String i : err) {
                result.addMessage(i);
            }
            sink.add(result);
        }

        public void recordResult(TestResult r) {
            if (result != null) {
                throw new IllegalStateException("VM had already published a result.");
            }
            result = r;
        }
    }

}
