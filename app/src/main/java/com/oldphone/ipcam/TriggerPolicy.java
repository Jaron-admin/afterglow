package com.oldphone.ipcam;

/**
 * Recording start rule for 「满足任一 / 全部满足」.
 * Motion and sound are instantaneous. Schedule and light hold over time.
 * With only continuous conditions selected, recording runs the whole time they hold.
 */
public final class TriggerPolicy {
    public static final class Selection {
        public final boolean motion;
        public final boolean sound;
        public final boolean schedule;
        public final boolean light;
        public final boolean matchAll;

        public Selection(boolean motion, boolean sound, boolean schedule, boolean light, boolean matchAll) {
            this.motion = motion;
            this.sound = sound;
            this.schedule = schedule;
            this.light = light;
            this.matchAll = matchAll;
        }

        public int count() {
            return (motion ? 1 : 0) + (sound ? 1 : 0) + (schedule ? 1 : 0) + (light ? 1 : 0);
        }

        public boolean hasInstant() { return motion || sound; }
    }

    /** What is true at this moment. Instant flags are edges, continuous flags are levels. */
    public static final class Moment {
        public final boolean motion;
        public final boolean sound;
        public final boolean schedule;
        public final boolean light;

        public Moment(boolean motion, boolean sound, boolean schedule, boolean light) {
            this.motion = motion;
            this.sound = sound;
            this.schedule = schedule;
            this.light = light;
        }
    }

    public static final class Decision {
        public final boolean sustain;
        public final boolean motion;
        public final boolean sound;
        public final boolean schedule;
        public final boolean light;

        public Decision(boolean sustain, boolean motion, boolean sound, boolean schedule, boolean light) {
            this.sustain = sustain;
            this.motion = motion;
            this.sound = sound;
            this.schedule = schedule;
            this.light = light;
        }

        public boolean any() { return sustain || motion || sound || schedule || light; }
    }

    private TriggerPolicy() {}

    public static Decision none() { return new Decision(false, false, false, false, false); }

    public static Decision decide(Selection selection, Moment moment) {
        if (selection == null || moment == null || selection.count() == 0) return none();
        if (!selection.matchAll) {
            boolean schedule = selection.schedule && moment.schedule;
            boolean light = selection.light && moment.light;
            return new Decision(schedule || light,
                    selection.motion && moment.motion,
                    selection.sound && moment.sound,
                    schedule, light);
        }
        if (selection.hasInstant()) {
            if (selection.schedule && !moment.schedule) return none();
            if (selection.light && !moment.light) return none();
            boolean motion = selection.motion && moment.motion;
            boolean sound = selection.sound && moment.sound;
            boolean fired = motion || sound;
            return new Decision(false, motion, sound,
                    fired && selection.schedule, fired && selection.light);
        }
        if (selection.schedule && !moment.schedule) return none();
        if (selection.light && !moment.light) return none();
        return new Decision(true, false, false, selection.schedule, selection.light);
    }

    public static String explanation(Selection selection) {
        if (selection == null || selection.count() == 0) return "请至少选择一个触发条件";
        if (!selection.matchAll) return "已选条件中任意一个满足就开始录制";
        return "已选的 " + selection.count() + " 个条件同时满足才开始录制";
    }
}
