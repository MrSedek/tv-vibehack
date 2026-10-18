package com.gathin.tvbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class HistoryTest {

    private static WatchItem item(String pkg, String id, String title, long pos, long dur, long at) {
        WatchItem w = new WatchItem();
        w.pkg = pkg;
        w.mediaId = id;
        w.title = title;
        w.positionMs = pos;
        w.durationMs = dur;
        w.updatedAt = at;
        return w;
    }

    @Test
    public void recordsAPausedVideo() {
        History h = new History();
        assertTrue(h.record(item("st", "abc", "Ramen", 16_714, 600_000, 1)));
        assertEquals(1, h.items().size());
        assertEquals(16_714, h.items().get(0).positionMs);
    }

    @Test
    public void ignoresSnapshotsTooEarlyToResume() {
        History h = new History();
        assertFalse(h.record(item("st", "abc", "Ramen", 4_000, 600_000, 1)));
        assertTrue(h.items().isEmpty());
    }

    @Test
    public void earlySnapshotKeepsTheEarlierProgress() {
        History h = new History();
        h.record(item("st", "abc", "Ramen", 300_000, 600_000, 1));
        assertFalse(h.record(item("st", "abc", "Ramen", 2_000, 600_000, 2)));
        assertEquals(300_000, h.items().get(0).positionMs);
    }

    @Test
    public void sameVideoReplacesTheOldEntryAndMovesToFront() {
        History h = new History();
        h.record(item("st", "abc", "Ramen", 20_000, 600_000, 1));
        h.record(item("vk", null, null, 275_748, 0, 2));
        h.record(item("st", "abc", "Ramen", 90_000, 600_000, 3));
        List<WatchItem> all = h.items();
        assertEquals(2, all.size());
        assertEquals("abc", all.get(0).mediaId);
        assertEquals(90_000, all.get(0).positionMs);
    }

    @Test
    public void keepsWhatWasKnownWhenTheNewSnapshotLacksIt() {
        // SmartTube opened from a link reports the id but not the title or artwork.
        History h = new History();
        WatchItem first = item("st", "abc", "Ramen", 20_000, 600_000, 1);
        first.subtitle = "Yummyboy";
        first.artUrl = "https://i.ytimg.com/vi/abc/mqdefault.jpg";
        h.record(first);
        h.record(item("st", "abc", null, 90_000, 0, 2));
        WatchItem now = h.items().get(0);
        assertEquals("Ramen", now.title);
        assertEquals("Yummyboy", now.subtitle);
        assertEquals("https://i.ytimg.com/vi/abc/mqdefault.jpg", now.artUrl);
        assertEquals(600_000, now.durationMs);
        assertEquals(90_000, now.positionMs);
    }

    @Test
    public void fillInAddsMissingTitleOnly() {
        History h = new History();
        h.record(item("st", "abc", null, 20_000, 600_000, 1));
        String key = h.items().get(0).key();
        assertTrue(h.fillIn(key, "Ramen", "Yummyboy"));
        assertEquals("Ramen", h.items().get(0).title);
        assertFalse(h.fillIn(key, "Other", "Someone"));
        assertEquals("Ramen", h.items().get(0).title);
        assertFalse(h.fillIn("missing", "x", "y"));
    }

    @Test
    public void finishedVideoIsRemoved() {
        History h = new History();
        h.record(item("st", "abc", "Ramen", 20_000, 600_000, 1));
        assertTrue(h.record(item("st", "abc", "Ramen", 590_000, 600_000, 2)));
        assertTrue(h.items().isEmpty());
    }

    @Test
    public void unknownDurationIsNeverFinished() {
        assertFalse(History.isFinished(10_000_000, 0));
        assertTrue(History.isFinished(571_000, 600_000));     // last 30 s of a 10 min video
        assertFalse(History.isFinished(560_000, 600_000));
        assertTrue(History.isFinished(6_900_000, 7_200_000)); // last 5 % of a 2 h film
    }

    @Test
    public void appWithoutIdOrTitleKeepsOneEntry() {
        History h = new History();
        h.record(item("vk", null, null, 100_000, 0, 1));
        h.record(item("vk", null, null, 275_748, 0, 2));
        assertEquals(1, h.items().size());
        assertEquals(275_748, h.items().get(0).positionMs);
    }

    @Test
    public void keepsOnlyTheNewestItems() {
        History h = new History();
        for (int i = 0; i < History.MAX_ITEMS + 5; i++) {
            h.record(item("st", "id" + i, "t" + i, 60_000, 600_000, i));
        }
        assertEquals(History.MAX_ITEMS, h.items().size());
        assertEquals("id" + (History.MAX_ITEMS + 4), h.items().get(0).mediaId);
    }

    @Test
    public void removedReturnsDroppedKeys() {
        History h = new History();
        WatchItem a = item("st", "abc", "Ramen", 20_000, 600_000, 1);
        h.record(a);
        h.record(item("st", "abc", "Ramen", 599_000, 600_000, 2));
        assertEquals(List.of(a.key()), h.takeRemovedKeys());
        assertTrue(h.takeRemovedKeys().isEmpty());
    }

    @Test
    public void positionMovesOnWhilePlaying() {
        assertEquals(15_000, History.positionAt(10_000, 1_000, 1f, true, 6_000));
        assertEquals(10_000, History.positionAt(10_000, 1_000, 1f, false, 6_000));
        assertEquals(20_000, History.positionAt(10_000, 1_000, 2f, true, 6_000));
    }
}
