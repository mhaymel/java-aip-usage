package org.example;

import org.example.WindowFit.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WindowFitTest {

    @Test
    void readsTheSizeThePageReports() {
        assertEquals(Optional.of(new Size(400, 42)), WindowFit.parse("400,42"));
        assertEquals(Optional.of(new Size(512, 66)), WindowFit.parse(" 512,66 "));
    }

    @Test
    void readsWhetherTheHeightMayBeResized() {
        assertEquals(Optional.of(new Size(400, 340, WindowFit.Resize.HEIGHT)), WindowFit.parse("400,340,1"));
        assertEquals(Optional.of(new Size(1200, 340, WindowFit.Resize.BOTH)), WindowFit.parse("1200,340,2"));
        assertEquals(Optional.of(new Size(400, 420, WindowFit.Resize.FIXED)), WindowFit.parse("400,420,3"), "content height, not resizable");
        assertEquals(Optional.of(new Size(415, 420, WindowFit.Resize.HEIGHT, "history")), WindowFit.parse("415,420,1,history"));
        assertEquals(Optional.of(new Size(1215, 420, WindowFit.Resize.BOTH, "log")), WindowFit.parse("1215,420,2,log"));
        assertEquals(Optional.of(new Size(415, 420, WindowFit.Resize.HEIGHT, "errors")), WindowFit.parse("415,420,1,errors"));
        assertEquals(Optional.of(new Size(400, 42, WindowFit.Resize.NONE)), WindowFit.parse("400,42,0"), "no panel named: null, as before");
        assertFalse(WindowFit.parse("400,420,3").orElseThrow().resizable());
        assertEquals(Optional.of(new Size(400, 34, WindowFit.Resize.NONE)), WindowFit.parse("400,34,0"));
        assertEquals(Optional.of(new Size(400, 34, WindowFit.Resize.NONE)), WindowFit.parse("400,34"));
    }

    @Test
    void aChangeOfResizabilityAloneIsAChange() {
        Optional<Size> applied = Optional.of(new Size(400, 68, WindowFit.Resize.NONE));
        assertEquals(Optional.of(new Size(400, 68, WindowFit.Resize.HEIGHT)), WindowFit.next("400,68,1", applied));
        assertEquals(Optional.of(new Size(400, 68, WindowFit.Resize.BOTH)), WindowFit.next("400,68,2", applied));
        assertEquals(Optional.empty(), WindowFit.next("400,68,1", Optional.of(new Size(400, 68, WindowFit.Resize.HEIGHT))));
    }

    @Test
    void keepsTheWindowWithinSaneLimits() {
        assertEquals(Optional.of(WindowFit.MIN), WindowFit.parse("1,1"));
        assertEquals(Optional.of(new Size(WindowFit.MIN.width(), 50)), WindowFit.parse("20,50"));
        assertEquals(Optional.of(WindowFit.MAX), WindowFit.parse("99999,99999"));
        assertEquals(Optional.of(new Size(2400, 42)), WindowFit.parse("5000,42"));
        assertEquals(Optional.of(new Size(400, 1600)), WindowFit.parse("400,2000"));
    }

    @Test
    void theBoundsAreAcceptedAsTheyAre() {
        assertEquals(Optional.of(WindowFit.MIN), WindowFit.parse("160,32"));
        assertEquals(Optional.of(WindowFit.MAX), WindowFit.parse("2400,1600"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "", "undefined", "null", "NaN,NaN", "400", "400,", ",42", "400;42", "400,42,4", "400,42,", "415,420,1,settings", "415,420,1,", "415,420,1,History", "415,420,,history", "400,42,1,1", "400, 42",
            "-5,10", "10,-5", "4.5,10", "0,0", "0,50", "50,0", "abc", "400x42", "999999,5", "5,999999",
            "99999999999999999999,5"})
    void ignoresAnythingThatIsNotASize(String reported) {
        assertEquals(Optional.empty(), WindowFit.parse(reported));
    }

    @Test
    void ignoresAnAnswerThatIsNotText() {
        assertEquals(Optional.empty(), WindowFit.parse(42));
        assertEquals(Optional.empty(), WindowFit.parse(new Object()));
        assertEquals(Optional.empty(), WindowFit.parse(Boolean.TRUE));
    }

    @Test
    void aNewSizeIsAppliedAndTheSameOneIsNot() {
        Optional<Size> none = Optional.empty();
        assertEquals(Optional.of(new Size(400, 42)), WindowFit.next("400,42", none));

        Optional<Size> applied = Optional.of(new Size(400, 42));
        assertEquals(Optional.empty(), WindowFit.next("400,42", applied), "already that size");
        assertEquals(Optional.of(new Size(400, 70)), WindowFit.next("400,70", applied));
        assertEquals(Optional.of(new Size(420, 42)), WindowFit.next("420,42", applied));
    }

    @Test
    void aReadingThatIsUnusableChangesNothing() {
        Optional<Size> applied = Optional.of(new Size(400, 42));

        assertEquals(Optional.empty(), WindowFit.next("undefined", applied));
        assertEquals(Optional.empty(), WindowFit.next(null, applied));
    }

    @Test
    void aSizeThatClampsToTheOneAlreadyAppliedChangesNothing() {
        Optional<Size> applied = Optional.of(WindowFit.MAX);

        assertEquals(Optional.empty(), WindowFit.next("5000,9000", applied));
    }
}
