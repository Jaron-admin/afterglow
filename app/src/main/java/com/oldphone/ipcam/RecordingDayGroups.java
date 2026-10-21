package com.oldphone.ipcam;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Local calendar folders: 今天, 昨天, then a date. */
public final class RecordingDayGroups {
    public static final class Item {
        public final String id;
        public final long startMs;
        public Item(String id, long startMs) {
            this.id = id;
            this.startMs = startMs;
        }
    }

    public static final class Section {
        public final String title;
        public final String day;
        public final List<Item> items;
        Section(String title, String day) {
            this.title = title;
            this.day = day;
            this.items = new ArrayList<Item>();
        }
    }

    private RecordingDayGroups() {}

    public static List<Section> group(List<Item> items, long nowMs, TimeZone zone) {
        if (zone == null) throw new IllegalArgumentException("zone");
        List<Item> sorted = new ArrayList<Item>();
        if (items != null) {
            for (Item item : items) if (item != null && item.id != null) sorted.add(item);
        }
        Collections.sort(sorted, new Comparator<Item>() {
            public int compare(Item left, Item right) {
                return Long.compare(right.startMs, left.startMs);
            }
        });
        Calendar now = Calendar.getInstance(zone);
        now.setTimeInMillis(nowMs);
        Calendar stamp = Calendar.getInstance(zone);
        List<Section> sections = new ArrayList<Section>();
        Section current = null;
        String currentDay = null;
        for (Item item : sorted) {
            stamp.setTimeInMillis(item.startMs);
            String day = String.format(Locale.US, "%04d-%02d-%02d",
                    stamp.get(Calendar.YEAR), stamp.get(Calendar.MONTH) + 1, stamp.get(Calendar.DAY_OF_MONTH));
            if (!day.equals(currentDay)) {
                current = new Section(title(now, stamp), day);
                sections.add(current);
                currentDay = day;
            }
            current.items.add(item);
        }
        return sections;
    }

    private static String title(Calendar now, Calendar stamp) {
        if (sameDay(now, stamp)) return "今天";
        Calendar yesterday = (Calendar) now.clone();
        yesterday.add(Calendar.DATE, -1);
        if (sameDay(yesterday, stamp)) return "昨天";
        int month = stamp.get(Calendar.MONTH) + 1;
        int day = stamp.get(Calendar.DAY_OF_MONTH);
        if (now.get(Calendar.YEAR) == stamp.get(Calendar.YEAR)) return month + "月" + day + "日";
        return stamp.get(Calendar.YEAR) + "年" + month + "月" + day + "日";
    }

    private static boolean sameDay(Calendar left, Calendar right) {
        return left.get(Calendar.YEAR) == right.get(Calendar.YEAR)
                && left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR);
    }
}
