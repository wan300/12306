package com.railhelper.ticket

import org.junit.Assert.*
import org.junit.Test

class PasswordRecoveryUrlTest {
    @Test fun allowsOfficialHttpsPages() {
        assertTrue(isOfficialRecoveryUrl("https://kyfw.12306.cn/otn/view/find_my_password.html"))
        assertTrue(isOfficialRecoveryUrl("https://www.12306.cn:443/"))
    }
    @Test fun rejectsImpersonatingOrUnsafeNavigation() {
        listOf("http://kyfw.12306.cn/", "https://12306.cn.evil.example/",
            "https://evil12306.cn/", "https://12306.cn@evil.example/", "file:///data/test",
            "javascript:alert(1)", "intent://12306.cn", "https://12306.cn:444/").forEach {
            assertFalse(it, isOfficialRecoveryUrl(it))
        }
    }
}
