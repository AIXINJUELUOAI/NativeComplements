package dev.local.sunwindowdemo;

import dev.local.sunwindowdemo.core.adaptation.CommunityAdaptation;
import org.junit.Test;

import static org.junit.Assert.*;

public class AdaptationRecordTest {
    @Test public void attributionRequiresBothExactLineagePropertiesAndHasSafeFeedback() {
        CommunityAdaptation record = CommunityAdaptation.match("24.0", "24.0-20261116-NIGHTLY-device");
        assertNotNull(record);
        assertEquals("LFRon", record.maintainer());
        assertEquals("https://github.com/LFRon", record.authorUrl());
        assertNull(record.maintainerFeedbackUrl());
        assertEquals(CommunityAdaptation.PROJECT_ISSUES, record.feedbackUrl());
        assertEquals(record.seenKey(), CommunityAdaptation.match(
                "24.0", "24.0-20270101-UNOFFICIAL-device").seenKey());
        for (String[] unknown : new String[][] {
                {null, null}, {"", ""}, {"24.0", null}, {null, "24.0-20261116-NIGHTLY-device"},
                {"23.0", "24.0-20261116-NIGHTLY-device"}, {"24.0", "23.0-20261116-NIGHTLY-device"},
                {"24.0", "124.0-fake"}, {"24.0", "24.0"}, {"24.0", "24.0-"},
                {"24.0", "LineageOS 24.0"}, {"Pixel", "dadaPixelOS"}
        }) assertNull(CommunityAdaptation.match(unknown[0], unknown[1]));
    }
}
