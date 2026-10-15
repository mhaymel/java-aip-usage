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
        assertEquals(Optional.of(new Size(520, 420, WindowFit.Resize.FIXED, "settings")), WindowFit.parse("520,420,3,settings"), "the settings are a panel too");
        assertEquals(Optional.of(new Size(520, 300, WindowFit.Resize.FIXED, "debug")), WindowFit.parse("520,300,3,debug"));
        assertEquals(Optional.empty(), WindowFit.parse("520,300,3,other"), "a name it does not know is no size");
        assertEquals(Optional.of(new Size(400, 42, WindowFit.Resize.NONE)), WindowFit.parse("400,42,0"), "no panel named: null, as before");
        assertFalse(WindowFit.parse("400,420,3").orElseThrow().resizable());
        assertEquals(Optional.of(new Size(400, 34, WindowFit.Resize.NONE)), WindowFit.parse("400,34,0"));
        assertEquals(Optional.of(new Size(400, 34, WindowFit.Resize.NONE)), WindowFit.parse("400,34"));
    }

    @Test
    void readsHowManyPixelsTheRowIsAwayBy() {
        assertEquals(Optional.of(new Size(400, 420, WindowFit.Resize.HEIGHT, "history", 0, 41)), WindowFit.parse("400,420,1,history,-41"));
        assertEquals(Optional.of(new Size(400, 800, WindowFit.Resize.HEIGHT, "cost+history", 420, 41)), WindowFit.parse("400,800,1,cost+history,420,-41"),
                "after the height the lowest list has alone");
        assertEquals(Optional.of(new Size(400, 120, WindowFit.Resize.FIXED, "cost", 0, 0)), WindowFit.parse("400,120,3,cost"), "none said: the row is there");
        assertEquals(Optional.empty(), WindowFit.parse("400,42,0,-41"), "only with a view named");
        assertEquals(8, WindowFit.parse("400,40,3,cost,-30").orElseThrow().away(), "never more than the least height leaves");
        Optional<Size> there = WindowFit.parse("400,420,1,history");
        assertEquals(WindowFit.parse("400,420,1,history,-41"), WindowFit.next("400,420,1,history,-41", there), "the row going is a change");
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
            "", "undefined", "null", "NaN,NaN", "400", "400,", ",42", "400;42", "400,42,5", "400,42,", "415,420,1,other", "415,420,1,", "415,420,1,History", "415,420,,history", "400,42,1,1", "400, 42",
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
    void onlyTheSettingsAndTheDebugSettingsKeepTheTitleBar() {
        for (String panel : new String[] {"settings", "debug"}) {
            assertEquals(true, WindowFit.parse("400,420,2," + panel).orElseThrow().keepsTitleBar(), panel);
        }
        // Since 0.87 a list has no title bar either: it has buttons to make it taller and shorter, and is moved by dragging it.
        for (String panel : new String[] {"history", "log", "errors"}) {
            assertEquals(false, WindowFit.parse("400,420,2," + panel).orElseThrow().keepsTitleBar(), panel);
        }
        assertEquals(false, WindowFit.parse("400,120,3,cost").orElseThrow().keepsTitleBar(), "the cost control has none, like the row alone");
        assertEquals(false, WindowFit.parse("400,42,0").orElseThrow().keepsTitleBar(), "no panel");
        assertEquals(false, WindowFit.parse("400,42").orElseThrow().keepsTitleBar());
    }

    @Test
    void theWidthAloneMayBeThePersonsToDrag() {
        Size about = WindowFit.parse("400,320,4,settings").orElseThrow();

        assertEquals(WindowFit.Resize.WIDTH, about.resize());
        assertEquals("settings", about.panel());
        assertEquals(true, about.resizable());
        assertEquals(true, about.widthIsThePersons());
        assertEquals(true, about.keepsTitleBar(), "there is an edge to drag");
        assertEquals(true, WindowFit.parse("400,320,2,log").orElseThrow().widthIsThePersons());
        assertEquals(false, WindowFit.parse("400,320,1,history").orElseThrow().widthIsThePersons());
        assertEquals(false, WindowFit.parse("400,320,3,settings").orElseThrow().widthIsThePersons(), "the other tabs are fixed");
        assertEquals(false, WindowFit.parse("400,320,3,settings").orElseThrow().resizable());
        assertEquals(Optional.empty(), WindowFit.parse("400,320,5,settings"), "there is no fifth");
        assertEquals(Optional.of(about), WindowFit.next("400,320,4,settings", WindowFit.parse("400,320,3,settings")),
                "the flag alone is a new size: going to About and from it is applied");
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

    @Test
    void severalViewsAreNamedTogetherAndTheHeightTheLowestListHasAloneFollows() {
        Size both = WindowFit.parse("520,640,1,cost+history,420").orElseThrow();

        assertEquals("cost+history", both.panel());
        assertEquals(java.util.List.of("cost", "history"), both.panels());
        assertEquals(true, both.several());
        assertEquals("history", both.lowestList(), "the cost control below it is no list");
        assertEquals(420, both.alone());
        assertEquals(WindowFit.Resize.HEIGHT, both.resize());

        Size all = WindowFit.parse("1300,1500,2,cost+history+log+errors,420").orElseThrow();
        assertEquals(java.util.List.of("cost", "history", "log", "errors"), all.panels());
        assertEquals("errors", all.lowestList());
        assertEquals(true, all.widthIsThePersons(), "the log is among them");

        Size one = WindowFit.parse("400,420,1,history").orElseThrow();
        assertEquals(java.util.List.of("history"), one.panels());
        assertEquals(false, one.several());
        assertEquals("history", one.lowestList());
        assertEquals(0, one.alone(), "one view says no height: the whole of it is the list's");
        assertEquals(java.util.List.of(), WindowFit.parse("400,42,0").orElseThrow().panels());
        assertEquals(null, WindowFit.parse("400,120,3,cost").orElseThrow().lowestList());
    }

    @Test
    void aReportThatNamesWhatIsNoViewIsNoSize() {
        assertEquals(Optional.empty(), WindowFit.parse("400,420,1,history+settings"), "the settings replace the views");
        assertEquals(Optional.empty(), WindowFit.parse("400,420,1,history+calendar"));
        assertEquals(Optional.empty(), WindowFit.parse("400,420,1,history+"));
        assertEquals(Optional.empty(), WindowFit.parse("400,420,1,cost+history+log+errors+log"), "there are four views");
        assertEquals(Optional.empty(), WindowFit.parse("400,420,1,cost+history,tall"));
        assertEquals(Optional.empty(), WindowFit.parse("400,420,1,history+history,420"), "a view twice");
        assertEquals(Optional.empty(), WindowFit.parse("400,420,1,history+cost,420"), "not in the order of the icons, the cost control first since 0.81");
        assertEquals(WindowFit.MAX.height(), WindowFit.parse("400,420,1,cost+history,99999").orElseThrow().alone(), "kept within the limits");
    }

    @Test
    void withSeveralViewsTheWindowHasItsTitleBarOnlyWhileTheSettingsAreAmongThem() {
        assertEquals(false, WindowFit.parse("520,640,1,cost+history,420").orElseThrow().keepsTitleBar(), "a list has none since 0.87");
        assertEquals(false, WindowFit.parse("1300,900,2,log+errors,420").orElseThrow().keepsTitleBar());
        assertEquals(true, WindowFit.parse("520,700,1,settings+cost+history,420").orElseThrow().keepsTitleBar());
        assertEquals(false, WindowFit.parse("400,120,3,cost").orElseThrow().keepsTitleBar(), "the cost control alone has none, as before");
    }

    @Test
    void anotherHeightOfTheLowestListIsANewSize() {
        Optional<Size> applied = WindowFit.parse("520,640,1,cost+history,420");

        assertEquals(Optional.empty(), WindowFit.next("520,640,1,cost+history,420", applied));
        assertEquals(true, WindowFit.next("520,720,1,cost+history,500", applied).isPresent());
        assertEquals(true, WindowFit.next("520,640,1,history,420", applied).isPresent(), "a view fewer is a new size");
    }

    @Test
    void theSettingsMayBeNamedFirstAmongTheViewsAndOnlyThere() {
        Size beside = WindowFit.parse("520,700,1,settings+cost+history,420").orElseThrow();
        assertEquals(java.util.List.of("settings", "cost", "history"), beside.panels());
        assertEquals("history", beside.lowestList(), "the settings are no list");
        assertEquals(true, beside.several());
        assertEquals(true, beside.keepsTitleBar());
        assertEquals(java.util.List.of("settings", "cost"), WindowFit.parse("520,300,3,settings+cost").orElseThrow().panels());
        assertEquals(WindowFit.Resize.WIDTH, WindowFit.parse("520,300,4,settings+cost").orElseThrow().resize(), "About beside the cost control");
        assertEquals(Optional.empty(), WindowFit.parse("520,700,1,cost+settings+history,420"), "first or nowhere");
        assertEquals(Optional.empty(), WindowFit.parse("520,700,1,settings+settings,420"));
        assertEquals(Optional.empty(), WindowFit.parse("520,700,1,settings+history+cost,420"), "the views still in the order of their icons");
    }
}
