package dev.local.sunwindowdemo.features.callblocking;

/** Pure number matching. A SIM slot is zero based and unknown slots always pass through. */
public final class CallBlockingRules {
    private CallBlockingRules() {}

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

    public static Decision decide(boolean enabled, int slotIndex, boolean sim1RejectAll,
            String[] sim1Patterns, boolean sim2FollowSim1, boolean sim2RejectAll,
            String[] sim2Patterns, String number) {
        if (!enabled || slotIndex < 0 || slotIndex > 1) return new Decision(false, "", "");
        boolean first = slotIndex == 0 || sim2FollowSim1;
        if (first ? sim1RejectAll : sim2RejectAll) return new Decision(true, "all_calls", "");
        String[] patterns = first ? sim1Patterns : sim2Patterns;
        if (patterns != null) for (String pattern : patterns) {
            if (matches(pattern, number)) return new Decision(true, "pattern", normalizePattern(pattern));
        }
        return new Decision(false, "", "");
    }

    /** Only the stranger rule can be waived by a repeat; explicit rules always win. */
    public static Decision stranger(Decision explicit, boolean rejectStrangers, boolean contactsReady,
            boolean knownContact, boolean repeatAllowed, boolean repeated, String number) {
        if (explicit.blocked || !rejectStrangers || !contactsReady || knownContact
                || normalizeNumber(number).isEmpty() || (repeatAllowed && repeated)) return explicit;
        return new Decision(true, "stranger", "");
    }

    public static String normalizeNumber(String number) {
        if (number == null || number.trim().matches("-[0-9]+")) return "";
        String value = number.replaceAll("[\\s()（）-]", "");
        if (value.startsWith("00")) value = "+" + value.substring(2);
        // Preserve country codes; only unambiguous Chinese mobile national presentation is aliased.
        if (value.matches("1[3-9][0-9]{9}")) value = "+86" + value;
        return value.length() <= 64 && value.matches("\\+?[0-9]+") ? value : "";
    }

    /** Bounded, in-memory only; the same Call callback never advances the repeat window. */
    public static final class Repeats {
        private final java.util.WeakHashMap<Object, Boolean> calls = new java.util.WeakHashMap<>();
        private final java.util.LinkedHashMap<String, Long> recent = new java.util.LinkedHashMap<>();
        public synchronized boolean observe(Object call, int subscriptionId, String number, long now) {
            Boolean previous = calls.get(call);
            if (previous != null) return previous;
            String normalized = normalizeNumber(number);
            boolean repeated = false;
            if (subscriptionId >= 0 && !normalized.isEmpty()) {
                recent.entrySet().removeIf(e -> now - e.getValue() > 180_000 || now < e.getValue());
                String key = subscriptionId + ":" + normalized;
                repeated = recent.containsKey(key);
                recent.remove(key);
                recent.put(key, now);
                // ponytail: 512 active caller keys; oldest loses only repeat exemption, raise if needed.
                if (recent.size() > 512) recent.remove(recent.keySet().iterator().next());
            }
            calls.put(call, repeated);
            return repeated;
        }
    }

    /** Formatting characters are ignored; only digits, a leading + and * are accepted. */
    public static String normalizePattern(String input) {
        if (input == null) throw new IllegalArgumentException("请输入号码规则");
        String value = input.replaceAll("[\\s()（）-]", "").replaceAll("\\*+", "*");
        if (value.isEmpty() || value.length() > 64 || !value.matches("\\+?[0-9*]+"))
            throw new IllegalArgumentException("只支持数字、开头的 + 和通配符 *");
        value = localNumber(value);
        if (value.isEmpty()) throw new IllegalArgumentException("请输入完整号码规则");
        return value;
    }

    private static String localNumber(String number) {
        // Match domestic presentation consistently, including network-provided +86 / 0086.
        if (number.startsWith("+86")) return number.substring(3);
        if (number.startsWith("0086")) return number.substring(4);
        return number;
    }

    public static boolean matches(String pattern, String number) {
        if (number == null || number.isEmpty()) return false;
        final String glob;
        try { glob = normalizePattern(pattern); }
        catch (IllegalArgumentException ignored) { return false; }
        String value = localNumber(number.replaceAll("[\\s()（）-]", ""));
        if (value.isEmpty() || !value.matches("\\+?[0-9]+")) return false;
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
}
