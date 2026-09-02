package com.nexusai.application.agent.mcp;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.util.Arrays;

/**
 * [S02 测试基建] 独立 JUnit Platform runner（不依赖 surefire/共享 target）·
 * 供并行会话干扰环境下运行聚焦测试。用法：TestRunner &lt;FQCN&gt;...
 */
public class TestRunner {

    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(Arrays.stream(args).map(DiscoverySelectors::selectClass).toList())
            .build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);
        TestExecutionSummary summary = listener.getSummary();
        long failed = summary.getTestsFailedCount();
        long errors = summary.getTestsFoundCount() - summary.getTestsSucceededCount()
            - failed - summary.getTestsSkippedCount();
        System.out.println("==== RESULT tests=" + summary.getTestsFoundCount()
            + " ok=" + summary.getTestsSucceededCount()
            + " failed=" + failed + " errors=" + errors
            + " skipped=" + summary.getTestsSkippedCount());
        for (TestExecutionSummary.Failure f : summary.getFailures()) {
            System.out.println("==== FAILURE: " + f.getTestIdentifier().getDisplayName());
            Throwable ex = f.getException();
            if (ex != null) {
                ex.printStackTrace(System.out);
            }
        }
        System.exit(summary.getTotalFailureCount() > 0 ? 1 : 0);
    }
}
