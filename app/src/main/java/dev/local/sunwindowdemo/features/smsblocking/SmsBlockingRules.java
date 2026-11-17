package dev.local.sunwindowdemo.features.smsblocking;

import dev.local.sunwindowdemo.features.callblocking.CallBlockingRules;
import java.util.Locale;
import java.util.regex.Pattern;

/** Pure SMS matching. A SIM slot is zero based and unknown slots always pass through. */
public final class SmsBlockingRules {
    private SmsBlockingRules() {}

    // Markers are matched case insensitively; a marker alone is not enough, digits must follow.
    private static final String[] VERIFICATION_WORDS = {"验证码", "校验码", "动态码", "确认码", "激活码",
            "verification code", "verify code", "security code", "one-time", "one time", "otp", "passcode"};
    private static final Pattern VERIFICATION_CODE = Pattern.compile("[0-9]{4,8}");

    public static final class Decision {
        public final boolean blocked;
        public final String reason;
        public final String matchedPattern;
        private Decision(boolean blocked, String reason, String pattern) {
            this.blocked = blocked;
            this.reason = reason;
            this.matchedPattern = pattern;
        }
    }

    /**
     * Explicit rules only: whole-SIM, sender patterns and body keywords. The stranger rule is a
     * separate step because it needs the contact snapshot and may be waived by a verification code.
     */
    public static Decision decide(boolean enabled, int slotIndex,
            boolean sim1RejectAll, String[] sim1Patterns, String[] sim1Keywords,
            boolean sim2FollowSim1, boolean sim2RejectAll, String[] sim2Patterns, String[] sim2Keywords,
            String address, String body) {
        if (!enabled || slotIndex < 0 || slotIndex > 1) return new Decision(false, "", "");
        boolean first = slotIndex == 0 || sim2FollowSim1;
        if (first ? sim1RejectAll : sim2RejectAll) return new Decision(true, "all_sms", "");
        String[] patterns = first ? sim1Patterns : sim2Patterns;
        if (patterns != null) for (String pattern : patterns) {
            if (matches(pattern, address)) return new Decision(true, "pattern", normalizedPattern(pattern));
        }
        String[] keywords = first ? sim1Keywords : sim2Keywords;
        if (keywords != null && body != null && !body.isEmpty()) for (String keyword : keywords) {
            if (containsKeyword(body, keyword)) return new Decision(true, "keyword", normalizedKeyword(keyword));
        }
        return new Decision(false, "", "");
    }

    /** Only the stranger rule can be waived by a verification code; explicit rules always win. */
    public static Decision stranger(Decision explicit, boolean rejectStrangers, boolean contactsReady,
            boolean knownContact, boolean verificationExempt, boolean verificationLike, String address) {
        if (explicit.blocked || !rejectStrangers || !contactsReady || knownContact
                || normalizeSender(address).isEmpty() || (verificationExempt && verificationLike)) return explicit;
        return new Decision(true, "stranger", "");
    }

    /**
     * A sender is either a phone number (same national presentation alias as call blocking) or an
     * alphanumeric service id. Unknown or unparsable senders fail open with an empty value.
     */
    public static String normalizeSender(String address) {
        if (address == null) return "";
        String value = address.replaceAll("[\\s()（）\\-]", "");
        if (value.isEmpty() || value.length() > 64) return "";
        String number = CallBlockingRules.normalizeNumber(value);
        if (!number.isEmpty()) return number;
        String letters = value.toUpperCase(Locale.ROOT).replaceAll("[^0-9A-Z]", "");
        return letters.length() <= 32 ? letters : "";
    }

    /** Formatting characters are ignored; digits, letters, a leading + and * are accepted. */
    public static String normalizePattern(String input) {
        if (input == null) throw new IllegalArgumentException("请输入号码或发送方规则");
        String value = input.replaceAll("[\\s()（）\\-]", "").replaceAll("\\*+", "*").toUpperCase(Locale.ROOT);
        if (value.isEmpty() || value.length() > 64 || !value.matches("\\+?[0-9A-Z*]+"))
            throw new IllegalArgumentException("只支持数字、字母、开头的 + 和通配符 *");
        return localNumber(value);
    }

    public static boolean matches(String pattern, String address) {
        final String glob = normalizedPattern(pattern);
        String value = normalizeSender(address);
        if (glob.isEmpty() || value.isEmpty()) return false;
        if (value.matches("\\+?[0-9]+")) value = localNumber(value);
        int p = 0, n = 0, star = -1, retry = 0;
        while (n < value.length()) {
            if (p < glob.length() && glob.charAt(p) == value.charAt(n)) { p++; n++; }
            else if (p < glob.length() && glob.charAt(p) == '*') { star = p++; retry = n; }
            else if (star >= 0) { p = star + 1; n = ++retry; }
            else return false;
        }
        while (p < glob.length() && glob.charAt(p) == '*') p++;
        return p == glob.length();
    }

    public static String normalizeKeyword(String input) {
        if (input == null) throw new IllegalArgumentException("请输入关键词");
        String value = input.replaceAll("\\p{Cntrl}", "").trim();
        if (value.isEmpty() || value.length() > 40) throw new IllegalArgumentException("关键词需为 1-40 个字符");
        return value;
    }

    public static boolean containsKeyword(String body, String keyword) {
        String target = normalizedKeyword(keyword);
        return !target.isEmpty() && body != null
                && body.toUpperCase(Locale.ROOT).contains(target.toUpperCase(Locale.ROOT));
    }

    /** Used only to decide whether the stranger rule may be waived, never to allow an explicit rule. */
    public static boolean looksLikeVerification(String body) {
        if (body == null || body.isEmpty()) return false;
        String upper = body.toUpperCase(Locale.ROOT);
        boolean marked = false;
        for (String word : VERIFICATION_WORDS) if (upper.contains(word.toUpperCase(Locale.ROOT))) { marked = true; break; }
        return marked && VERIFICATION_CODE.matcher(body).find();
    }

    private static String normalizedPattern(String pattern) {
        try { return normalizePattern(pattern); } catch (IllegalArgumentException ignored) { return ""; }
    }

    private static String normalizedKeyword(String keyword) {
        try { return normalizeKeyword(keyword); } catch (IllegalArgumentException ignored) { return ""; }
    }

    /** Match domestic presentation consistently, including network-provided +86 / 0086. */
    private static String localNumber(String number) {
        if (number.startsWith("+86")) return number.substring(3);
        if (number.startsWith("0086")) return number.substring(4);
        return number;
    }
}
