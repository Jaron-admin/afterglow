package com.oldphone.ipcam;

public final class TriggerPolicyTest {
    static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    static TriggerPolicy.Selection sel(boolean motion, boolean sound, boolean schedule,
                                       boolean light, boolean matchAll) {
        return new TriggerPolicy.Selection(motion, sound, schedule, light, matchAll);
    }

    static TriggerPolicy.Moment now(boolean motion, boolean sound, boolean schedule, boolean light) {
        return new TriggerPolicy.Moment(motion, sound, schedule, light);
    }

    public static void main(String[] args) {
        TriggerPolicy.Selection or = sel(true, true, false, false, false);
        check(!or.matchAll, "default combination is 满足任一");
        check("已选条件中任意一个满足就开始录制".equals(TriggerPolicy.explanation(or)), "or copy");
        TriggerPolicy.Decision motionOnly = TriggerPolicy.decide(or, now(true, false, false, false));
        check(motionOnly.motion && !motionOnly.sound && !motionOnly.sustain, "or motion starts alone");
        TriggerPolicy.Decision soundOnly = TriggerPolicy.decide(or, now(false, true, false, false));
        check(soundOnly.sound && !soundOnly.motion, "or sound starts alone");
        check(!TriggerPolicy.decide(or, now(false, false, true, true)).any(),
                "unselected continuous conditions do not start an or recording");

        TriggerPolicy.Selection and = sel(true, true, true, false, true);
        check(and.matchAll, "全部满足");
        check("已选的 3 个条件同时满足才开始录制".equals(TriggerPolicy.explanation(and)), "and copy");
        check(!TriggerPolicy.decide(and, now(true, false, false, false)).any(),
                "and ignores an instant event while the schedule is closed");
        TriggerPolicy.Decision gated = TriggerPolicy.decide(and, now(true, false, true, false));
        check(gated.motion && gated.schedule && !gated.sustain,
                "and starts from the instant event when every continuous condition holds");
        check(!TriggerPolicy.decide(and, now(false, false, true, false)).any(),
                "and does not record a schedule by itself when an instant trigger is selected");

        TriggerPolicy.Selection continuous = sel(false, false, true, true, true);
        check(!TriggerPolicy.decide(continuous, now(false, false, true, false)).sustain,
                "continuous and stops when any condition fails");
        TriggerPolicy.Decision held = TriggerPolicy.decide(continuous, now(false, false, true, true));
        check(held.sustain && held.schedule && held.light && !held.motion,
                "continuous and records the whole time every condition holds");
        check(!TriggerPolicy.decide(continuous, now(true, true, false, false)).any(),
                "unselected instant events do not start a continuous-only rule");

        TriggerPolicy.Selection lightOr = sel(false, false, false, true, false);
        check(TriggerPolicy.decide(lightOr, now(false, false, false, true)).sustain, "or light holds");
        check(!TriggerPolicy.decide(lightOr, now(false, false, false, false)).sustain, "or light releases");

        check(!TriggerPolicy.decide(sel(false, false, false, false, false),
                now(true, true, true, true)).any(), "nothing selected never records");
        check("请至少选择一个触发条件".equals(TriggerPolicy.explanation(
                sel(false, false, false, false, true))), "empty copy");
        check(TriggerPolicy.none() != null && !TriggerPolicy.decide(null, now(true, true, true, true)).any(),
                "missing selection records nothing");
    }
}
