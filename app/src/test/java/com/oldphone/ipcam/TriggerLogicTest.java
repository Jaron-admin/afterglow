package com.oldphone.ipcam;

import org.junit.Test;

/** Same cases as tests/recording so Gradle and the Windows suite both cover them. */
public class TriggerLogicTest {
    @Test public void matchAnyAndMatchAll() { TriggerPolicyTest.main(new String[0]); }
    @Test public void lightTable() { LightPolicyTest.main(new String[0]); }
    @Test public void followStreamResolution() { StreamPresetsTest.main(new String[0]); }
}
