/*
 * Copyright (c) 2005, 2014, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.jcstress.infra.collectors;

import org.openjdk.jcstress.infra.Status;
import org.openjdk.jcstress.infra.grading.ReportUtils;
import org.openjdk.jcstress.infra.grading.TestGrading;
import org.openjdk.jcstress.infra.runners.TestConfig;
import org.openjdk.jcstress.util.Environment;
import org.openjdk.jcstress.util.HashMultiset;
import org.openjdk.jcstress.util.Multiset;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @author Aleksey Shipilev (aleksey.shipilev@oracle.com)
 */
public class TestResult implements Serializable {

    private final TestConfig config;
    private final Status status;
    private final Multiset<String> states;
    private volatile Environment env;
    private final List<String> messages;
    private final List<String> vmOut;
    private final List<String> vmErr;

    public TestResult(TestConfig config, Status status) {
        this.config = config;
        this.status = status;
        this.states = new HashMultiset<>();
        this.messages = new ArrayList<>();
        this.vmOut = new ArrayList<>();
        this.vmErr = new ArrayList<>();
    }

    public void addState(String result, long count) {
        states.add(result, count);
    }

    public void addMessage(String msg) {
        if (ReportUtils.skipMessage(msg)) return;
        messages.add(msg);
    }

    public void addMessages(Collection<String> msgs) {
        for (String m : msgs) {
            addMessage(m);
        }
    }

    public void addVMOut(String msg) {
        if (ReportUtils.skipMessage(msg)) return;
        vmOut.add(msg);
    }

    public void addVMOuts(Collection<String> msgs) {
        for (String m : msgs) {
            addVMOut(m);
        }
    }

    public void addVMErr(String msg) {
        if (ReportUtils.skipMessage(msg)) return;
        vmErr.add(msg);
    }

    public void addVMErrs(Collection<String> msgs) {
        for (String m : msgs) {
            addVMErr(m);
        }
    }

    public void setEnv(Environment e) {
        env = e;
    }

    public String getName() {
        return config.name;
    }

    public Environment getEnv() {
        return env;
    }

    public Status status() {
        return status;
    }

    public List<String> getMessages() {
        return messages;
    }

    public List<String> getVmOut() {
        return vmOut;
    }

    public List<String> getVmErr() {
        return vmErr;
    }

    public long getTotalCount() {
        return states.size();
    }

    public long getCount(String s) {
        return states.count(s);
    }

    public Collection<String> getStateKeys() {
        return states.keys();
    }

    public TestConfig getConfig() {
        return config;
    }

    public TestGrading grading() {
        return TestGrading.grade(this);
    }

    public boolean hasSamples() {
        return !states.isEmpty();
    }
}
