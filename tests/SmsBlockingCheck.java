import dev.local.sunwindowdemo.features.smsblocking.SmsBlockingRules;

/** Dependency free checks for the pure SMS rule layer; run by tests/verify_smsblocking.py. */
public final class SmsBlockingCheck {
    private static final String[] NONE = {};

    public static void main(String[] args) {
        check("952*".equals(SmsBlockingRules.normalizePattern(" 952** ")), "pattern collapse");
        check("BANK*".equals(SmsBlockingRules.normalizePattern("bank*")), "pattern uppercase");
        check(SmsBlockingRules.matches("952**", "+86 95212345"), "national presentation");
        check(SmsBlockingRules.matches("BANK*", "BankofChina"), "service id glob");
        check(!SmsBlockingRules.matches("952", "9521"), "prefix is not a match");
        check(!SmsBlockingRules.matches("*", "!!!"), "unparsable sender fails open");
        check(!SmsBlockingRules.matches("9[0-9]*", "9521"), "brackets are literal");

        check(SmsBlockingRules.containsKeyword("回复TD退订", "退订"), "keyword");
        check(SmsBlockingRules.containsKeyword("Your OTP is 1234", "otp"), "keyword case");
        check(!SmsBlockingRules.containsKeyword(null, "otp"), "missing body");
        check(SmsBlockingRules.looksLikeVerification("您的验证码是 123456"), "code marker with digits");
        check(!SmsBlockingRules.looksLikeVerification("您的验证码即将上线"), "marker without digits");

        check(SmsBlockingRules.decide(true, 0, false, new String[]{"95588"}, NONE, false, false, NONE, NONE,
                "+8695588", "").blocked, "explicit pattern");
        check(!SmsBlockingRules.decide(true, 1, false, new String[]{"95588"}, NONE, false, false, NONE, NONE,
                "95588", "").blocked, "slot isolation");
        check(SmsBlockingRules.decide(true, 1, false, new String[]{"95588"}, NONE, true, false, NONE, NONE,
                "95588", "").blocked, "follow sim1");
        check(SmsBlockingRules.decide(true, 1, false, NONE, NONE, false, true, NONE, NONE, "10086", "").blocked,
                "reject all on sim2");
        check(!SmsBlockingRules.decide(false, 0, true, NONE, NONE, false, true, NONE, NONE, "10086", "").blocked,
                "disabled feature");
        check(SmsBlockingRules.decide(true, 0, false, NONE, new String[]{"贷款"}, false, false, NONE, NONE,
                "10086", "需要贷款吗").blocked, "keyword rule");
        check(!SmsBlockingRules.decide(true, -1, true, NONE, NONE, false, true, NONE, NONE, "10086", "").blocked,
                "unknown slot passes");

        SmsBlockingRules.Decision pass = SmsBlockingRules.decide(true, 0, false, NONE, NONE, false, false, NONE, NONE,
                "13800138000", "hi");
        check(!SmsBlockingRules.stranger(pass, true, false, false, false, false, "13800138000").blocked,
                "contacts unavailable fails open");
        check(!SmsBlockingRules.stranger(pass, true, true, true, false, false, "13800138000").blocked, "known contact");
        check(SmsBlockingRules.stranger(pass, true, true, false, false, false, "13800138000").blocked, "stranger");
        check(!SmsBlockingRules.stranger(pass, true, true, false, true, true, "13800138000").blocked,
                "verification exemption");
        check(!SmsBlockingRules.stranger(pass, true, true, false, false, false, "").blocked, "empty sender");
        System.out.println("SMS blocking rules: PASS");
    }

    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
    }
}
