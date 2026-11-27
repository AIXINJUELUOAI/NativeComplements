package dev.local.sunwindowdemo.features.minusone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import dev.local.sunwindowdemo.features.minusone.WidgetCanvas.Slot;
import java.util.Map;
import org.junit.Test;

public class WidgetCanvasTest {
    @Test public void twoByTwoWidgetsCanShareFourColumns() {
        Map<Integer, Slot> occupied = Map.of(1, new Slot(0, 0, 2, 2));
        assertEquals(new Slot(2, 0, 2, 2), WidgetCanvas.firstFit(2, 2, 4, occupied));
    }

    @Test public void dragPushesChainIntoAvailableCells() {
        Map<Integer, Slot> occupied = Map.of(1, new Slot(0, 0, 1, 1),
                2, new Slot(1, 0, 1, 1), 3, new Slot(2, 0, 1, 1));
        var drop = new Slot(1, 0, 1, 1);
        assertEquals(Map.of(2, new Slot(2, 0, 1, 1), 3, new Slot(3, 0, 1, 1)),
                WidgetCanvas.rearrange(drop, 4, 1, occupied, 1));
    }

    @Test public void resizeFailsWhenNoVacancyCanHoldOtherWidget() {
        Map<Integer, Slot> occupied = Map.of(1, new Slot(0, 0, 2, 2), 2, new Slot(2, 0, 2, 2));
        assertNull(WidgetCanvas.rearrange(new Slot(0, 0, 3, 2), 4, 2, occupied, 1));
    }
}
