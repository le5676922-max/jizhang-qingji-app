package com.example.jizhang.automation

import org.junit.Assert.*
import org.junit.Test

class DisplayCaptureWindowPolicyTest {
    private val receipt=ScreenNodeBounds(0,100,1080,2200)
    @Test fun targetWindowItselfIsNotAnOverlay() {
        assertFalse(DisplayCaptureWindowPolicy.unsafeOtherWindow(3,receipt,3,receipt,true,false))
    }
    @Test fun statusAndNavigationBarsOutsideReceiptMayRemainVisible() {
        assertFalse(DisplayCaptureWindowPolicy.unsafeOtherWindow(3,receipt,4,ScreenNodeBounds(0,0,1080,100),false,false))
        assertFalse(DisplayCaptureWindowPolicy.unsafeOtherWindow(3,receipt,4,ScreenNodeBounds(0,2200,1080,2400),false,false))
    }
    @Test fun headsUpNotificationOrAccessibilityOverlayOverReceiptRejectsDisplayCapture() {
        assertTrue(DisplayCaptureWindowPolicy.unsafeOtherWindow(3,receipt,4,ScreenNodeBounds(20,120,1060,450),false,false))
        assertTrue(DisplayCaptureWindowPolicy.unsafeOtherWindow(3,receipt,4,ScreenNodeBounds(900,1500,1050,1700),false,false))
    }
    @Test fun otherApplicationAndKeyboardAreConservativelyRejected() {
        val nonOverlapping=ScreenNodeBounds(1080,0,2000,2200)
        assertTrue(DisplayCaptureWindowPolicy.unsafeOtherWindow(3,receipt,4,nonOverlapping,true,false))
        assertTrue(DisplayCaptureWindowPolicy.unsafeOtherWindow(3,receipt,4,nonOverlapping,false,true))
    }
}
