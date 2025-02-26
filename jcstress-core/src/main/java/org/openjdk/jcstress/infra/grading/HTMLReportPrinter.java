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
package org.openjdk.jcstress.infra.grading;


import org.openjdk.jcstress.Options;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.infra.Status;
import org.openjdk.jcstress.infra.TestInfo;
import org.openjdk.jcstress.infra.collectors.InProcessCollector;
import org.openjdk.jcstress.infra.collectors.TestResult;
import org.openjdk.jcstress.infra.runners.TestList;
import org.openjdk.jcstress.util.*;

import java.awt.*;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.*;
import java.util.List;
import java.util.function.Predicate;

/**
 * Prints HTML reports.
 *
 * @author Aleksey Shipilev (aleksey.shipilev@oracle.com)
 */
public class HTMLReportPrinter {

    private final String resultDir;
    private final InProcessCollector collector;
    private int cellStyle = 1;

    public HTMLReportPrinter(Options opts, InProcessCollector collector) {
        this.collector = collector;
        this.resultDir = opts.getResultDest();
        new File(resultDir).mkdirs();
    }

    public void work() throws FileNotFoundException {
        List<TestResult> byName = ReportUtils.mergedByName(collector.getTestResults());
        Collections.sort(byName, Comparator.comparing(TestResult::getName));

        PrintWriter output = new PrintWriter(resultDir + "/index.html");

        printHeader(output);

        output.println("<table width=\"100%\" cellspacing=\"20\">");
        output.println("<tr>");
        output.println("<td>");

        {
            int passedCount = 0;
            int failedCount = 0;
            int sanityFailedCount = 0;
            for (TestResult result : byName) {
                if (result.status() == Status.NORMAL) {
                    if (result.grading().isPassed) {
                        passedCount++;
                    } else {
                        failedCount++;
                    }
                } else {
                    if (result.status() == Status.API_MISMATCH) {
                        sanityFailedCount++;
                    } else {
                        failedCount++;
                    }
                }
            }

            int totalCount = passedCount + failedCount;
            int passedProgress = totalCount > 0 ? (passedCount * 100 / totalCount) : 0;
            int failedProgress = totalCount > 0 ? (failedCount * 100 / totalCount) : 100;

            if (failedCount > 0) {
                output.println("<p class=\"endResult failed\">");
            } else {
                output.println("<p class=\"endResult passed\">");
            }
            output.println("" + passedProgress + "%");
            if (sanityFailedCount > 0) {
                output.println(" <span class=\"special\">(" + sanityFailedCount + " tests skipped)</span>");
            }
            output.println("</p>");

            output.println("<table width=\"100%\" cellpadding=\"3\" cellspacing=\"0\">");
            if (passedProgress > 0) {
                output.println("<tr><td width=\"" + passedProgress + "%\" class=\"passedProgress\">&nbsp;</td></tr>");
            }
            if (failedProgress > 0) {
                output.println("<tr><td width=\"" + failedProgress + "%\" class=\"failedProgress\">&nbsp;</td></tr>");
            }
            output.println("<tr><td nowrap><b>Overall pass rate:</b> " + passedCount + "/" + (passedCount + failedCount) + "&nbsp;</td></tr>");
            output.println("</table>");

            output.println("<br>");
        }
        output.println("</td>");
        output.println("<td width=100>");

        {
            SortedMap<String, String> env = getEnv(byName);

            output.println("<table>");
            for (Map.Entry<String, String> entry : env.entrySet()) {
                output.println("<tr>");
                output.println("<td nowrap>" + entry.getKey() + "</td>");
                output.println("<td nowrap>" + entry.getValue() + "</td>");
                output.println("</tr>");
            }
            output.println("</table>");
        }

        output.println("</td>");
        output.println("</tr>");
        output.println("</table>");

        printXTests(byName, output,
                "FAILED tests",
                "Strong asserts were violated. Correct implementations should have no assert failures here.",
                r -> r.status() == Status.NORMAL && !r.grading().isPassed);

        printXTests(byName, output,
                "ERROR tests",
                "Tests break for some reason, other than failing the assert. Correct implementations should have none.",
                r -> r.status() != Status.NORMAL && r.status() != Status.API_MISMATCH);

        printXTests(byName, output,
                "INTERESTING tests",
                "Some interesting behaviors observed. This is for the plain curiosity.",
                r -> r.status() == Status.NORMAL && r.grading().hasInteresting);

        printXTests(byName, output,
                "All tests",
                "",
                r -> true);

        printFooter(output);

        output.close();

        emitTestReports(ReportUtils.byName(collector.getTestResults()));
    }

    private SortedMap<String, String> getEnv(List<TestResult> ts) {
        SortedMap<String, String> env = new TreeMap<>();
        for (TestResult result : ts) {
            if (result != null) {
                for (Map.Entry<String, String> kv : result.getEnv().entries().entrySet()) {
                    String key = kv.getKey();
                    String value = kv.getValue();
                    String lastV = env.get(key);
                    if (lastV == null) {
                        env.put(key, value);
                    } else {
                        // Some VMs have these keys pre-populated with the command line,
                        // which can have port definitions, PIDs, etc, and naturally
                        // clash from launch to launch.
                        if (key.equals("cmdLine")) continue;
                        if (key.equals("launcher")) continue;

                        if (!lastV.equalsIgnoreCase(value)) {
                            System.err.println("Mismatched environment for key = " + key + ", was = " + lastV + ", now = " + value);
                        }
                    }
                }
            }
        }
        return env;
    }

    private void printFooter(PrintWriter output) {
        output.println("</body>");
        output.println("</html>");
    }

    private void printHeader(PrintWriter output) {
        output.println("\n" +
                "<html>\n" +
                "<head>\n" +
                "<title>Java Concurrency Stress test report</title>\n" +
                " <style type=\"text/css\">\n" +
                "   * { font-family: Arial; }\n" +
                "   table { font-size: 9pt; }\n" +
                "   a { color: #000000; }\n" +
                "   .progress { padding: 0px; }\n" +
                "   .header { text-align: left; }\n" +
                "   .section1 { font-size: 12pt; background-color: #BDB76B; color: #000000; font-weight: bold;}\n" +
                "   .section2 { font-size: 12pt; background-color: #F0E68C; color: #000000; font-weight: bold;}\n" +
                "   .cell1 { background-color: #FAFAD2; }\n" +
                "   .cell2 { background-color: #EEE8AA; }\n" +
                "   .passedProgress { background-color: #00AA00; color: #FFFFFF; text-align: center; font-weight: bold; }\n" +
                "   .failedProgress { background-color: #FF0000; color: #FFFFFF; text-align: center; font-weight: bold; }\n" +
                "   .passed { color: #00AA00; text-align: center; font-weight: bold; }\n" +
                "   .failed { color: #FF0000; text-align: center; font-weight: bold; }\n" +
                "   .interesting { color: #0000FF; text-align: center; font-weight: bold; }\n" +
                "   .spec { color: #AAAA00; text-align: center; font-weight: bold; }\n" +
                "   .endResult { font-size: 48pt; text-align: center; font-weight: bold; }\n" +
                " </style>\n" +
                "</head>\n" +
                "<body>");
    }

    private void printXTests(List<TestResult> byName,
                             PrintWriter output,
                             String header,
                             String subheader,
                             Predicate<TestResult> filterResults) {
        output.println("<hr>");
        output.println("<h3>" + header + "</h3>");
        output.println("<p>" + subheader + "</p>");
        output.println("<table cellspacing=0 cellpadding=3 width=\"100%\">");

        boolean hadAnyTests = false;
        for (TestResult result : byName) {
            if (filterResults.test(result)) {
                if (result.status() == Status.NORMAL) {
                    emitTest(output, result);
                } else {
                    emitTestFailure(output, result);
                }
                hadAnyTests = true;
            }
        }

        output.println("</table>");
        if (!hadAnyTests) {
            output.println("None!");
            output.println("<br>");
        }

        output.println("<br>");
    }

    public void emitTest(PrintWriter output, TestResult result) {
        cellStyle = 3 - cellStyle;
        output.println("<tr class=\"cell" + cellStyle + "\">");
        output.println("<td>&nbsp;&nbsp;&nbsp;<a href=\"" + result.getName() + ".html\">" + StringUtils.chunkName(result.getName()) + "</a></td>");
        output.printf("<td>%s</td>", getRoughCount(result));

        TestGrading grading = result.grading();
        if (grading.isPassed) {
            output.println("<td class=\"passed\">PASSED</td>");
        } else {
            output.println("<td class=\"failed\">FAILED</td>");
        }

        if (grading.hasInteresting) {
            output.println("<td class=\"interesting\">INTERESTING</td>");
        } else {
            output.println("<td class=\"interesting\"></td>");
        }
        output.println("<td class=\"passed\"></td>");
        output.println("</tr>");
    }

    public void emitTestFailure(PrintWriter output, TestResult result) {
        cellStyle = 3 - cellStyle;
        output.println("<tr class=\"cell" + cellStyle + "\">");
        output.println("<td>&nbsp;&nbsp;&nbsp;<a href=\"" + result.getName() + ".html\">" + StringUtils.chunkName(result.getName()) + "</a></td>");
        output.println("<td></td>");
        switch (result.status()) {
            case API_MISMATCH:
                output.println("<td class=\"interesting\">API MISMATCH</td>");
                output.println("<td class=\"interesting\"></td>");
                output.println("<td class=\"interesting\"></td>");
                output.println("<td class=\"interesting\">Sanity check failed, API mismatch?</td>");
                break;
            case TEST_ERROR:
            case CHECK_TEST_ERROR:
                output.println("<td class=\"failed\">ERROR</td>");
                output.println("<td class=\"failed\"></td>");
                output.println("<td class=\"failed\"></td>");
                output.println("<td class=\"failed\">Error while running the test</td>");
                break;
            case TIMEOUT_ERROR:
                output.println("<td class=\"failed\">ERROR</td>");
                output.println("<td class=\"failed\"></td>");
                output.println("<td class=\"failed\"></td>");
                output.println("<td class=\"failed\">Timeout while running the test</td>");
                break;
            case VM_ERROR:
                output.println("<td class=\"failed\">VM ERROR</td>");
                output.println("<td class=\"failed\"></td>");
                output.println("<td class=\"failed\"></td>");
                output.println("<td class=\"failed\">Error running the VM</td>");
                break;
        }
        output.println("</tr>");
    }

    public static String getRoughCount(TestResult r) {
        long sum = r.getTotalCount();
        if (sum > 10) {
            return "10<sup>" + (int) Math.floor(Math.log10(sum)) + "</sup>";
        } else {
            return String.valueOf(sum);
        }
    }

    private void emitTestReports(Multimap<String, TestResult> multiByName) {
        multiByName.keys().parallelStream().forEach(name -> {
            try {
                TestInfo test = TestList.getInfo(name);
                PrintWriter local = new PrintWriter(resultDir + "/" + name + ".html");
                emitTestReport(local, multiByName.get(name), test);
                local.close();
            } catch (FileNotFoundException e) {
                // do nothing
            }
        });
    }

    public void emitTestReport(PrintWriter o, Collection<TestResult> results, TestInfo test) {
        printHeader(o);

        o.println("<h1>" + test.name() + "</h1>");

        o.println("<h3>Description and references</h3>");
        o.println("<p>" + test.description() + "</p>");

        for (String ref : test.refs()) {
            o.println("<p><a href=\"" + ref + "\">" + ref + "</a></p>");
        }

        List<TestResult> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.comparing((TestResult t) -> StringUtils.join(t.getConfig().jvmArgs, ",")));

        o.println("<h3>Environment</h3>");
        o.println("<table>");
        for (Map.Entry<String, String> entry : getEnv(sorted).entrySet()) {
            o.println("<tr>");
            o.println("<td nowrap>" + entry.getKey() + "</td>");
            o.println("<td nowrap>" + entry.getValue() + "</td>");
            o.println("</tr>");
        }
        o.println("</table>");

        o.println("<h3>Test configurations</h3>");

        o.println("<table>");
        int configs = 0;
        for (TestResult r : sorted) {
            o.println("<tr>");
            o.println("<td nowrap><b>TC " + (configs + 1) + "</b></td>");
            o.println("<td nowrap>" + r.getConfig() + "</td>");
            o.println("</tr>");
            configs++;
        }
        o.println("</table>");

        o.println("<h3>Observed states</h3>");

        Set<String> keys = new TreeSet<>();
        for (TestResult r : sorted) {
            keys.addAll(r.getStateKeys());
        }

        o.println("<table cellpadding=5>");
        o.println("<tr>");
        o.println("<th>Observed state</th>");
        for (int c = 0; c < configs; c++) {
            o.println("<th nowrap>TC " + (c+1) + "</th>");
        }
        o.println("<th>Expectation</th>");
        o.println("<th>Interpretation</th>");
        o.println("</tr>");

        for (String key : keys) {
            o.println("<tr>");
            o.println("<td align='center'>" + key + "</td>");

            String description = "";
            Expect expect = null;

            for (TestResult r : sorted) {
                for (GradingResult c : r.grading().gradingResults) {
                    if (c.id.equals(key)) {
                        o.println("<td align='right' width='" + 30D/configs + "%' bgColor=" + selectHTMLColor(c.expect, c.count == 0) + ">" + c.count + "</td>");
                        description = c.description;
                        expect = c.expect;
                    }
                }
            }

            o.println("<td>" + expect + "</td>");
            o.println("<td>" + description + "</td>");
            o.println("</tr>");
        }

        o.println("<tr>");
        o.println("<td></td>");
        for (TestResult r : sorted) {
            String color = ReportUtils.statusToPassed(r) ? "green" : "red";
            String label = ReportUtils.statusToLabel(r);
            o.println("<td align='center' bgColor='" + color + " '>" + label + "</td>");
        }
        o.println("<td></td>");
        o.println("<td></td>");
        o.println("</tr>");

        o.println("</table>");

        o.println("<h3>Messages</h3>");

        for (TestResult r : sorted) {
            if (!r.getMessages().isEmpty()) {
                o.println("<p><b>" + r.getConfig() + "</b></p>");
                o.println("<pre>");
                for (String data : r.getMessages()) {
                    o.println(data);
                }
                o.println("</pre>");
                o.println();
            }
        }

        o.println("<h3>VM Output Streams</h3>");

        for (TestResult r : sorted) {
            if (!r.getVmOut().isEmpty()) {
                o.println("<p><b>" + r.getConfig() + "</b></p>");
                o.println("<pre>");
                for (String data : r.getVmOut()) {
                    o.println(data);
                }
                o.println("</pre>");
                o.println();
            }
        }

        o.println("<h3>VM Error Streams</h3>");

        for (TestResult r : sorted) {
            if (!r.getVmErr().isEmpty()) {
                o.println("<p><b>" + r.getConfig() + "</b></p>");
                o.println("<pre>");
                for (String data : r.getVmErr()) {
                    o.println(data);
                }
                o.println("</pre>");
                o.println();
            }
        }

        printFooter(o);
    }

    public String selectHTMLColor(Expect type, boolean isZero) {
        String rgb = Integer.toHexString(selectColor(type, isZero).getRGB());
        return "#" + rgb.substring(2, rgb.length());
    }

    public Color selectColor(Expect type, boolean isZero) {
        switch (type) {
            case ACCEPTABLE:
                return isZero ? Color.LIGHT_GRAY : Color.GREEN;
            case FORBIDDEN:
                return isZero ? Color.LIGHT_GRAY : Color.RED;
            case ACCEPTABLE_INTERESTING:
                return isZero ? Color.LIGHT_GRAY : Color.CYAN;
            case UNKNOWN:
                return Color.RED;
            default:
                throw new IllegalStateException();
        }
    }

}
