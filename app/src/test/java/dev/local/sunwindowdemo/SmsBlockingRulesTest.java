package dev.local.sunwindowdemo;

import dev.local.sunwindowdemo.features.smsblocking.SmsBlockingRules;
import org.junit.Test;
import static org.junit.Assert.*;

public class SmsBlockingRulesTest {
    private static final String[] NO_RULES = {};

    @Test public void senderPatternsSupportNumbersAndServiceIds() {
        assertEquals("952*", SmsBlockingRules.normalizePattern(" 952** "));
        assertEquals("BANK*", SmsBlockingRules.normalizePattern("bank*"));
        assertTrue(SmsBlockingRules.matches("952**", "+86 95212345"));
        assertTrue(SmsBlockingRules.matches("13*8000", "13800138000"));
        assertTrue(SmsBlockingRules.matches("BANK*", "BankofChina"));
        assertTrue(SmsBlockingRules.matches("*95588", "+8695588"));
        assertFalse(SmsBlockingRules.matches("952", "9521"));
        assertFalse(SmsBlockingRules.matches("952*", null));
        assertFalse(SmsBlockingRules.matches("9[0-9]*", "9521"));
        // Unknown or unparsable senders never match: rules must fail open.
        assertFalse(SmsBlockingRules.matches("*", ""));
        assertFalse(SmsBlockingRules.matches("*", "!!!"));
    }

    @Test public void keywordsIgnoreCaseAndControlCharacters() {
        assertEquals("退订", SmsBlockingRules.normalizeKeyword(" 退订 "));
        assertTrue(SmsBlockingRules.containsKeyword("回复TD退订", "退订"));
        assertTrue(SmsBlockingRules.containsKeyword("Your OTP is 1234", "otp"));
        assertFalse(SmsBlockingRules.containsKeyword("余额提醒", "退订"));
        assertFalse(SmsBlockingRules.containsKeyword(null, "退订"));
        try {
            SmsBlockingRules.normalizeKeyword("   ");
            fail("blank keyword accepted");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void verificationCodeMarkersNeedDigits() {
        assertTrue(SmsBlockingRules.looksLikeVerification("【某银行】您的验证码是 123456，请勿泄露"));
        assertTrue(SmsBlockingRules.looksLikeVerification("Your verification code is 4321"));
        assertFalse(SmsBlockingRules.looksLikeVerification("【某银行】您的验证码即将上线"));
        assertFalse(SmsBlockingRules.looksLikeVerification("余额 123456 元"));
        assertFalse(SmsBlockingRules.looksLikeVerification(null));
    }

    @Test public void explicitRulesRespectSlotAndFollow() {
        String[] first = {"95588"};
        String[] second = {"1069*"};
        String[] noKeywords = NO_RULES;
        assertFalse(SmsBlockingRules.decide(false, 0, true, first, noKeywords, false, true, second, noKeywords, "95588", "").blocked);
        assertTrue(SmsBlockingRules.decide(true, 0, false, first, noKeywords, false, false, second, noKeywords, "+8695588", "").blocked);
        assertFalse(SmsBlockingRules.decide(true, 1, false, first, noKeywords, false, false, second, noKeywords, "95588", "").blocked);
        assertTrue(SmsBlockingRules.decide(true, 1, false, first, noKeywords, true, false, second, noKeywords, "95588", "").blocked);
        assertTrue(SmsBlockingRules.decide(true, 1, false, first, noKeywords, false, true, second, noKeywords, "10086", "").blocked);
        assertTrue(SmsBlockingRules.decide(true, 1, false, first, noKeywords, false, false, second, noKeywords, "106900001", "").blocked);
        assertFalse(SmsBlockingRules.decide(true, -1, true, first, noKeywords, false, true, second, noKeywords, "10086", "").blocked);
    }

    @Test public void keywordRulesNeedBodyAndStayPerSlot() {
        String[] keywords = {"退订", "贷款"};
        assertTrue(SmsBlockingRules.decide(true, 0, false, NO_RULES, keywords, false, false, NO_RULES, NO_RULES, "10086", "回复退订").blocked);
        assertFalse(SmsBlockingRules.decide(true, 0, false, NO_RULES, keywords, false, false, NO_RULES, NO_RULES, "10086", "余额提醒").blocked);
        assertFalse(SmsBlockingRules.decide(true, 0, false, NO_RULES, keywords, false, false, NO_RULES, NO_RULES, "10086", null).blocked);
        // SIM 2 does not inherit SIM 1 keywords unless it follows it.
        assertFalse(SmsBlockingRules.decide(true, 1, false, NO_RULES, keywords, false, false, NO_RULES, NO_RULES, "10086", "贷款").blocked);
        assertTrue(SmsBlockingRules.decide(true, 1, false, NO_RULES, keywords, true, false, NO_RULES, NO_RULES, "10086", "贷款").blocked);
    }

    @Test public void strangerRuleFailsOpenAndHonoursVerificationExemption() {
        SmsBlockingRules.Decision pass = SmsBlockingRules.decide(true, 0, false, NO_RULES, NO_RULES,
                false, false, NO_RULES, NO_RULES, "13800138000", "hi");
        assertFalse(pass.blocked);
        assertFalse(SmsBlockingRules.stranger(pass, true, false, false, false, false, "13800138000").blocked);
        assertFalse(SmsBlockingRules.stranger(pass, true, true, true, false, false, "13800138000").blocked);
        assertTrue(SmsBlockingRules.stranger(pass, true, true, false, false, false, "13800138000").blocked);
        assertFalse(SmsBlockingRules.stranger(pass, true, true, false, true, true, "13800138000").blocked);
        assertTrue(SmsBlockingRules.stranger(pass, true, true, false, true, false, "13800138000").blocked);
        // Alphanumeric service ids can never be a contact, so they stay strangers.
        assertTrue(SmsBlockingRules.stranger(pass, true, true, false, false, false, "BANK").blocked);
        // An explicit rule and a sendership without any address are never overridden here.
        SmsBlockingRules.Decision blocked = SmsBlockingRules.decide(true, 0, false, new String[]{"138*"}, NO_RULES,
                false, false, NO_RULES, NO_RULES, "13800138000", "");
        assertTrue(SmsBlockingRules.stranger(blocked, true, true, true, true, true, "13800138000").blocked);
        assertFalse(SmsBlockingRules.stranger(pass, true, true, false, false, false, "").blocked);
    }
}
