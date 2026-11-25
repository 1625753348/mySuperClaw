package com.example.enterpriseagent.intent.definition;

import com.example.enterpriseagent.intent.IntentModel.Definition;
import com.example.enterpriseagent.intent.IntentArtifacts.FilledSlots;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 槽位取值限制在已登记字段。同一个字段的非法新值不会被旧值掩盖。 */
public final class SlotExtractor {
    private static final String NUMBER = "-?(?:[0-9]+|[零〇一二两三四五六七八九十百千]+)";
    private static final Pattern PERSON = Pattern.compile("[赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦许何吕施张孔曹严华魏陶姜谢邹喻柏苏潘葛奚范彭鲁韦马方任袁柳鲍史唐费廉岑薛雷贺倪汤胡罗钟徐高郭林熊刘邓徐][\\p{IsHan}]{1,2}?(?=的|查|报销|考勤|处理|提交|交|$|[，,。\\s])");
    private static final Set<String> PERSON_STOP_WORDS = Set.of("周报", "周会", "周末", "周一", "周二", "周三", "周四",
            "周五", "周六", "周日", "周天", "周期间");
    private static final Pattern CITY = Pattern.compile("北京|上海|广州|深圳|杭州|成都|武汉|南京|重庆|天津|西安|苏州|长沙|青岛|厦门|郑州|宁波|福州|济南|沈阳|哈尔滨|昆明|合肥|南昌");
    private static final Pattern DOCUMENT_ID = Pattern.compile(
            "(?:待办(?:单)?|单据|审批单|报销单|报销)(?:编号|号码|号)?\\s*(?:[:：=]\\s*)?(?:(?:是|为)\\s*)?([A-Z0-9][A-Z0-9_-]*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDER_ID = Pattern.compile(
            "(?<![a-z0-9])(SO[-]?[A-Z0-9]{2,}|[0-9]{6,20})(?![a-z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern LABELED_ORDER_REPLY = Pattern.compile(
            "订单(?:编号|号码|号)\\s*(?:[:：=]|是|为)?\\s*" + ORDER_ID.pattern(), Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTRACT_ID = Pattern.compile(
            "(?<![a-z0-9])(CT[-]?[A-Z0-9]{2,})(?![a-z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Set<String> NUMBER_FIELDS = Set.of("attendees", "floor", "duration", "amount");
    private final Clock clock;

    public SlotExtractor(Clock clock) { this.clock = clock; }

    public boolean isDocumentIdReply(String text) {
        return DOCUMENT_ID.matcher(text.strip().replaceFirst("[。.!！?？]+$", "").stripTrailing()).matches();
    }

    public boolean isLabeledIdentifierReply(String text) {
        return isDocumentIdReply(text)
                || LABELED_ORDER_REPLY.matcher(text.strip().replaceFirst("[。.!！?？]+$", "").stripTrailing()).matches();
    }

    public FilledSlots extract(Definition definition, String text, Map<String, Object> given) {
        var values = new LinkedHashMap<String, Object>();
        var invalid = new ArrayList<String>();
        var schema = definition.parameterSchema();
        var fields = new ArrayList<>(schema.required());
        fields.addAll(schema.optional());
        for (String field : fields) {
            Object value = given.containsKey(field) ? given.get(field) : detect(field, text);
            if (value == null && !given.containsKey(field)) continue;
            Object normalized = normalize(field, value);
            if (normalized == null) invalid.add(field);
            else values.put(field, normalized);
        }
        return new FilledSlots(Map.copyOf(values), java.util.List.copyOf(invalid));
    }

    private Object detect(String field, String text) {
        return switch (field) {
            case "person" -> PERSON.matcher(text).results().map(result -> result.group())
                    .filter(name -> !PERSON_STOP_WORDS.contains(name)).findFirst().orElse(null);
            case "city" -> first(CITY, text, 0);
            case "orderId" -> identifierOutsideDocument(ORDER_ID, text);
            case "contractId" -> identifierOutsideDocument(CONTRACT_ID, text);
            case "documentId" -> first(DOCUMENT_ID, text, 1);
            case "customerId" -> first("客户\\s*([A-Z0-9][A-Z0-9_-]{0,39})", text, 1);
            case "date" -> date(text);
            case "period" -> first("本周|这周|上周|上个月|上月|本月|这个月|本季度|上季度|去年|今年|今天|昨天", text, 0);
            case "approvalStatus", "reimbursementStatus" -> oaStatus(text);
            case "time" -> time(text);
            case "attendees" -> first("(" + NUMBER + ")\\s*(?:个)?(?:人|位)", text, 1);
            case "floor" -> first("(" + NUMBER + ")\\s*(?:楼|层)", text, 1);
            case "duration" -> duration(text);
            case "amount" -> money(text);
            case "leaveType" -> first("年假|事假|病假|调休|婚假|产假", text, 0);
            case "reason" -> first("(?:事由|原因|理由)(?:是|为|[：:])?\\s*([^，,。；;]+)", text, 1);
            case "attachment" -> first("\\b(file_[A-Za-z0-9_-]{4,80})\\b", text, 1);
            case "recipient" -> first("主管|负责人|经理|法务|财务", text, 0);
            case "departments" -> departments(text);
            case "topic" -> first("交周报|提交周报|交报告|提交|开会", text, 0);
            default -> null;
        };
    }

    private static String oaStatus(String text) {
        String label = first("待审核|待审批|待处理|待办|已通过|已批准|已驳回|已拒绝|已撤回|已付款", text, 0);
        if (label == null) return null;
        return switch (label) {
            case "待审核", "待审批", "待处理", "待办" -> "pending";
            case "已通过", "已批准" -> "approved";
            case "已驳回", "已拒绝" -> "rejected";
            case "已撤回" -> "withdrawn";
            case "已付款" -> "paid";
            default -> throw new IllegalArgumentException("OA 状态无法识别");
        };
    }

    private static String identifierOutsideDocument(Pattern pattern, String text) {
        // 补参只尝试已有任务的槽位。显式单据编号即使与订单号格式相同，也应归到 documentId。
        var documentRanges = DOCUMENT_ID.matcher(text).results().toList();
        return pattern.matcher(text).results().filter(match -> documentRanges.stream()
                        .noneMatch(document -> match.start(1) < document.end(1) && document.start(1) < match.end(1)))
                .map(match -> match.group(1)).findFirst().orElse(null);
    }

    private String date(String text) {
        String explicit = first("\\b[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}\\b", text, 0);
        if (explicit != null) return explicit;
        String relative = first("大后天|后天|明天|今天|昨天", text, 0);
        if (relative == null) return null;
        return LocalDate.now(clock).plusDays(switch (relative) {
            case "昨天" -> -1;
            case "明天" -> 1;
            case "后天" -> 2;
            case "大后天" -> 3;
            default -> 0;
        }).toString();
    }

    private static String time(String text) {
        var match = Pattern.compile("(上午|下午|晚上|中午|早上)?\\s*(" + NUMBER + ")(?:[:：]([0-9]{2})|点(半|[0-9]{1,2}分)?)").matcher(text);
        if (!match.find()) return null;
        int hour;
        try {
            hour = number(match.group(2));
        } catch (NumberFormatException ex) {
            return "invalid";
        }
        String meridiem = match.group(1);
        if (meridiem != null && hour < 12 && Set.of("下午", "晚上", "中午").contains(meridiem)) hour += 12;
        int minute = match.group(3) != null ? number(match.group(3))
                : match.group(4) == null ? 0 : "半".equals(match.group(4)) ? 30 : number(match.group(4).replace("分", ""));
        return "%02d:%02d".formatted(hour, minute);
    }

    private static Object duration(String text) {
        String days = first("(" + NUMBER + ")\\s*(?:个)?(?:天|小时)", text, 1);
        if (days != null) return days;
        String word = first("\\b(one|two|three|four|five|six|seven)\\s+days?\\b", text, 1);
        return word == null ? null : java.util.List.of("one", "two", "three", "four", "five", "six", "seven").indexOf(word) + 1;
    }

    private static Object money(String text) {
        var match = Pattern.compile("(?:金额|合计|一笔|人民币)?\\s*(-?[0-9]+(?:\\.[0-9]{1,2})?|[一二两三四五六七八九十百千]+)\\s*(万元?|元)?").matcher(text);
        while (match.find()) {
            if (match.group(2) == null && !match.group(0).strip().matches("^(金额|合计|一笔|人民币).*")
                    && !text.strip().equals(match.group(0).strip())) continue;
            try {
                var amount = match.group(1).matches("-?[0-9]+(?:\\.[0-9]{1,2})?")
                        ? new java.math.BigDecimal(match.group(1)) : java.math.BigDecimal.valueOf(number(match.group(1)));
                return match.group(2) != null && match.group(2).startsWith("万")
                        ? amount.movePointRight(4) : amount;
            } catch (NumberFormatException ex) { return "invalid"; }
        }
        return null;
    }

    private static Object departments(String text) {
        var values = new ArrayList<String>();
        for (String team : java.util.List.of("法务", "财务", "采购", "人事")) if (text.contains(team)) values.add(team);
        return values.isEmpty() ? null : values;
    }

    private static Object normalize(String field, Object value) {
        if (value == null) return null;
        if ("departments".equals(field)) {
            return value instanceof java.util.List<?> list && !list.isEmpty() && list.size() <= 8
                    && list.stream().allMatch(item -> item instanceof String s && !s.isBlank() && s.length() <= 24)
                    ? java.util.List.copyOf(list) : null;
        }
        if (NUMBER_FIELDS.contains(field)) {
            try {
                if ("amount".equals(field)) {
                    var amount = new java.math.BigDecimal(value.toString());
                    return amount.signum() > 0 && amount.compareTo(new java.math.BigDecimal("100000000")) <= 0
                            && amount.stripTrailingZeros().scale() <= 2 ? amount : null;
                }
                int number = number(value.toString());
                int limit = switch (field) { case "attendees" -> 1000; case "floor" -> 150; default -> 365; };
                return number > 0 && number <= limit ? number : null;
            } catch (NumberFormatException ex) { return null; }
        }
        if (!(value instanceof String input) || input.isBlank() || input.length() > 256) return null;
        try {
            return switch (field) {
                case "date" -> LocalDate.parse(input).toString();
                case "time" -> LocalTime.parse(input).toString();
                case "attachment" -> input.matches("file_[A-Za-z0-9_-]{4,80}") ? input : null;
                case "approvalStatus" -> input.matches("pending|approved|rejected|withdrawn") ? input : null;
                case "reimbursementStatus" -> input.matches("pending|approved|rejected|withdrawn|paid") ? input : null;
                default -> field.endsWith("Id")
                        ? input.matches("[A-Za-z0-9_-]{1,64}") ? input.strip().toUpperCase(java.util.Locale.ROOT) : null
                        : input.strip();
            };
        } catch (java.time.DateTimeException ex) { return null; }
    }

    private static int number(String text) {
        if (text.matches("-?[0-9]+")) return Integer.parseInt(text);
        int total = 0, digit = 0;
        for (char character : text.replace('两', '二').replace('〇', '零').toCharArray()) {
            int position = "零一二三四五六七八九".indexOf(character);
            if (position >= 0) digit = position;
            else {
                int unit = switch (character) { case '十' -> 10; case '百' -> 100; case '千' -> 1000; default -> 0; };
                if (unit == 0) throw new NumberFormatException();
                total += (digit == 0 ? 1 : digit) * unit;
                digit = 0;
            }
        }
        return total + digit;
    }

    private static String first(String expression, String text, int group) {
        return first(Pattern.compile(expression, Pattern.CASE_INSENSITIVE), text, group);
    }
    private static String first(Pattern expression, String text, int group) {
        var match = expression.matcher(text);
        return match.find() ? match.group(group) : null;
    }
}
